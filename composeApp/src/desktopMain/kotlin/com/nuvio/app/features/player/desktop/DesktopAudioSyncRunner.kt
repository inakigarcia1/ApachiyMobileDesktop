package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.autosync.AutoSyncTimelineRetimeResult
import com.nuvio.app.features.autosync.renderRetimedSrt
import com.nuvio.app.features.player.AudioSyncSettings
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.audiosync.SileroVad
import com.nuvio.app.features.player.audiosync.SpeechAnalyzer
import com.nuvio.app.features.player.audiosync.SpeechTimeline
import com.nuvio.app.features.player.audiosync.DesktopAsrModel
import com.nuvio.app.features.player.audiosync.DesktopSherpaRuntime
import com.nuvio.app.features.player.audiosync.DesktopSherpaSpeechToText
import com.nuvio.app.features.player.audiosync.SubtitleAudioAligner
import com.nuvio.app.features.player.audiosync.SubtitleSpeechTrack
import com.nuvio.app.features.player.audiosync.asr.HeardWord
import com.nuvio.app.features.player.audiosync.asr.WordAnchorMatcher
import com.nuvio.app.features.player.audiosync.loadSileroVadWeights
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

internal class DesktopAudioSyncRunner(
    private val scope: CoroutineScope,
    private val controller: NativePlayerController,
    private val onDevNotice: (String) -> Unit,
) {
    private val log = Logger.withTag("DesktopAudioSync")
    private var job: Job? = null

    fun start(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        headerLines: Array<String>,
        subtitleUrl: String,
        subtitleHeaders: Map<String, String>,
        isStillSelected: () -> Boolean,
    ): Boolean {
        if (!AudioSyncSettings.fallbackEnabled.value) return false
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            if (!isActive) return@launch
            onDevNotice("Audio Sync started")
            val body = runCatching {
                httpGetTextWithHeaders(url = subtitleUrl, headers = subtitleHeaders)
            }.getOrNull()
            if (body == null) {
                onDevNotice("Audio Sync failed: subtitle could not be loaded")
                return@launch
            }
            val cues = PlayerSubtitleCueParser.parse(body, subtitleUrl)
            val dialogue = cues.map { Triple(it.startTimeMs, it.endTimeMs, it.text) }
            val track = SubtitleSpeechTrack.fromCues(dialogue)
            if (track.size < 4) {
                onDevNotice("Audio Sync failed: not enough dialogue cues")
                return@launch
            }
            val weights = loadSileroVadWeights()
            if (weights == null) {
                onDevNotice("Audio Sync failed: VAD weights unavailable")
                return@launch
            }
            val timeline = SpeechTimeline()
            val vad = SileroVad(weights)
            val analyzer = SpeechAnalyzer(vad, timeline)
            val durationMs = withContext(Dispatchers.Main) {
                controller.currentDurationMs()
            }.coerceAtLeast(60_000L)
            val spots = listOf(
                durationMs / 20,
                durationMs / 4,
                durationMs / 2,
            ).distinct().filter { it > 30_000L }
            val wavDir = File(System.getProperty("java.io.tmpdir"), "apachiy-audiosync").apply { mkdirs() }
            val sampledWavs = ArrayList<Pair<File, Long>>()
            for ((index, spotMs) in spots.withIndex()) {
                if (!isStillSelected()) return@launch
                val wav = File(wavDir, "spot-$index-${spotMs}.wav")
                val decoded = NativePlayerBridge.sampleSourceAudioWav(
                    sourceUrl = sourceUrl,
                    headerLines = headerLines,
                    startPositionMs = spotMs,
                    durationMs = 45_000L,
                    audioTrackId = -1,
                    outputWavPath = wav.absolutePath,
                ) || decodeWithFfmpeg(
                    sourceUrl = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    startMs = spotMs,
                    durationMs = 45_000L,
                    output = wav,
                )
                if (!decoded || !wav.isFile) continue
                feedWavIntoTimeline(wav, analyzer, spotMs)
                sampledWavs += wav to spotMs
            }
            if (sampledWavs.isEmpty()) {
                onDevNotice("Audio Sync failed: could not sample audio")
                return@launch
            }
            val estimate = SubtitleAudioAligner.estimate(
                segments = timeline.segments(),
                track = track,
                minShiftMs = -120_000.0,
                maxShiftMs = 120_000.0,
            )
            val retimed = when {
                estimate != null && !estimate.atSearchEdge && estimate.peak >= 0.2 -> {
                    val scale = estimate.scale
                    val shiftMs = estimate.shiftMs - SubtitleAudioAligner.DETECTOR_BIAS_MS
                    buildRetimed(cues, scale, shiftMs, estimate.peak)
                }
                else -> {
                    onDevNotice("Audio Sync: trying speech recognition fallback")
                    trySherpaAlign(cues, dialogue, sampledWavs)
                }
            }
            sampledWavs.forEach { (wav, _) -> wav.delete() }
            if (retimed == null) {
                onDevNotice("Audio Sync failed: no reliable match")
                return@launch
            }
            if (!isStillSelected()) return@launch
            val applied = withContext(Dispatchers.Main) {
                controller.replaceExternalSubtitleBody(subtitleUrl, retimed)
            }
            if (applied) {
                onDevNotice("Audio Sync succeeded")
            } else {
                onDevNotice("Audio Sync failed: could not apply sync")
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun buildRetimed(
        cues: List<SubtitleSyncCue>,
        scale: Double,
        shiftMs: Double,
        peak: Double,
    ): String = renderRetimedSrt(
        cues,
        AutoSyncTimelineRetimeResult(
            cues = emptyList(),
            groups = emptyList(),
            targetCoverage = peak,
            referenceCoverage = peak,
            skippedTargetCues = 0,
            skippedReferenceCues = 0,
            longestTargetSkipRun = 0,
            averageGroupCost = 0.0,
            oneToOneGroups = 0,
            oneToTwoGroups = 0,
            twoToOneGroups = 0,
            oneToThreeGroups = 0,
            threeToOneGroups = 0,
            twoToTwoGroups = 0,
            confident = true,
            alignmentScale = scale,
            alignmentInterceptMs = shiftMs,
        ),
    )

    private fun trySherpaAlign(
        cues: List<SubtitleSyncCue>,
        dialogue: List<Triple<Long, Long, String>>,
        samples: List<Pair<File, Long>>,
    ): String? {
        if (!DesktopSherpaRuntime.ensureLoaded()) return null
        if (!DesktopAsrModel.ensureDownloaded()) return null
        val matcher = WordAnchorMatcher(dialogue)
        if (matcher.isEmpty) return null
        DesktopSherpaSpeechToText(DesktopAsrModel.directory()).use { stt ->
            val heard = ArrayList<HeardWord>()
            samples.forEachIndexed { segment, (wav, mediaOffsetMs) ->
                val pcm = readWavPcm16(wav) ?: return@forEachIndexed
                val words = stt.transcribe(pcm)
                val offsetSec = mediaOffsetMs / 1_000.0
                words.forEach { (timeSec, text) ->
                    heard += HeardWord(offsetSec + timeSec, text, segment)
                }
            }
            if (heard.size < 4) return null
            val fit = matcher.fit(heard) ?: return null
            if (!fit.isConfident) return null
            val shiftMs = fit.shiftSec * 1_000.0
            return buildRetimed(cues, fit.scale, shiftMs, fit.score / 10.0)
        }
    }

    private fun readWavPcm16(wav: File): FloatArray? = runCatching {
        AudioSystem.getAudioInputStream(wav).use { stream ->
            val targetFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                16_000f,
                16,
                1,
                2,
                16_000f,
                false,
            )
            val converted = AudioSystem.getAudioInputStream(targetFormat, stream)
            val bytes = converted.readAllBytes()
            val samples = FloatArray(bytes.size / 2)
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) {
                samples[i] = buffer.short / 32768f
            }
            samples
        }
    }.getOrNull()

    private fun feedWavIntoTimeline(
        wav: File,
        analyzer: SpeechAnalyzer,
        mediaOffsetMs: Long,
    ) {
        AudioSystem.getAudioInputStream(wav).use { stream ->
            val targetFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                16_000f,
                16,
                1,
                2,
                16_000f,
                false,
            )
            val converted = AudioSystem.getAudioInputStream(targetFormat, stream)
            val bytes = converted.readAllBytes()
            val samples = FloatArray(bytes.size / 2)
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) {
                samples[i] = buffer.short / 32768f
            }
            val timeUs = mediaOffsetMs * 1_000L
            analyzer.accept(samples, samples.size, SileroVad.SAMPLE_RATE, timeUs)
        }
    }

    private fun decodeWithFfmpeg(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        startMs: Long,
        durationMs: Long,
        output: File,
    ): Boolean {
        val ffmpeg = listOf("ffmpeg", "ffmpeg.exe").firstOrNull { command ->
            runCatching {
                ProcessBuilder(command, "-version").start().waitFor() == 0
            }.getOrDefault(false)
        } ?: return false
        val headerArgs = sourceHeaders.flatMap { (k, v) -> listOf("-headers", "$k: $v\r\n") }
        val args = buildList {
            add(ffmpeg)
            add("-y")
            add("-ss")
            add((startMs / 1000.0).toString())
            add("-t")
            add((durationMs / 1000.0).toString())
            addAll(headerArgs)
            add("-i")
            add(sourceUrl)
            add("-vn")
            add("-ac")
            add("1")
            add("-ar")
            add("16000")
            add(output.absolutePath)
        }
        return runCatching {
            ProcessBuilder(args).redirectErrorStream(true).start().waitFor() == 0 && output.isFile
        }.getOrDefault(false)
    }
}

private fun AudioInputStream.readAllBytes(): ByteArray {
    val out = ByteArrayOutputStream()
    copyTo(out)
    return out.toByteArray()
}
