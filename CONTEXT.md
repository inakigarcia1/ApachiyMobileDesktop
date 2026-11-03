# Playback

How Apachiy gets a subtitle onto a specific video file. The picture starts as soon as the player is ready. Subtitles attach in the background when needed.

## Language

**Release**:
One specific video file, identified by the title id plus the file hash, size, and filename.
_Avoid_: stream, source, episode

**Embedded Spanish subtitle**:
A subtitle track inside the release whose language is Spanish, in any format.
_Avoid_: Spanish sub, hardcoded sub

**Community subtitle**:
The subtitle file Community returns for a release: Spanish when its score is high enough, otherwise the English file Community chose.
_Avoid_: Spanish sub, reference, addon sub

**OpenSubtitles subtitle**:
A Spanish file fetched from the public OpenSubtitles Stremio addon. Auto Sync aligns it to embedded timing references on the device before it is shown. Community is not called when this path succeeds.
_Avoid_: addon sub, external sub

**Timing reference**:
An embedded dialogue track whose cue times the device uses to move a community or OpenSubtitles subtitle. It never leaves the device.
_Avoid_: Reference, sample, harvest, partial SRT

**Loading screen**:
The wait after play, before the picture starts, while the player prepares the release. It no longer waits for Community or OpenSubtitles; subtitle fetch runs in parallel with playback.
_Avoid_: overlay, spinner
