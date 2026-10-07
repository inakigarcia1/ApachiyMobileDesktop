package com.nuvio.app.features.player.audiosync.asr

/** Speech recogniser returning (seconds from segment start, word) pairs. */
internal fun interface SpeechToText {
    fun transcribe(samples: FloatArray): List<Pair<Double, String>>
}
