# Playback

How Apachiy gets a subtitle onto a specific video file. The picture starts once that subtitle is chosen. Aligning it to the file happens on the device, after the picture is already moving.

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

**Timing reference**:
An embedded dialogue track whose cue times the device uses to move a community subtitle. It never leaves the device.
_Avoid_: Reference, sample, harvest, partial SRT

**Loading screen**:
The wait after play, before the picture starts, while Apachiy resolves the release and, when there is no embedded Spanish subtitle, asks Community for a community subtitle.
_Avoid_: overlay, spinner
