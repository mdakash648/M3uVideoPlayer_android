# Prompt: Fix Missing/Freezing Audio on Android TV (ExoPlayer/Media3) for DD+/Atmos Content

## Context
This is an Android IPTV/M3U player app (`M3uVideoPlayer_android`) with a dual-engine architecture:
- **v1** used LibVLC only.
- **v2** uses Media3/ExoPlayer as the primary playback engine.

## Problem

On **Android TV / Firestick**, movie files whose audio track is encoded in
**Dolby Digital Plus (E-AC3/DDP5.1), Dolby Atmos, or AC3/DD5.1** exhibit broken audio playback:

1. **Auto/default audio track** → video plays fine, but there is **no sound at all**.
2. **Manually selecting the audio track** in-app → playback **freezes/buffers/stalls** instead of playing.
3. **Live TV channels** (same app, same engine) play video **and** audio correctly — because live streams use AAC/MP2 audio, which Android's built-in hardware decoders support natively.
4. On **mobile**, the exact same movie files play with audio just fine.
5. The **older v1 build (LibVLC-only engine)** plays the same movie files with audio correctly on the same TV — confirming this is not a network/URL/file issue, but an **audio codec decoding capability issue specific to the current engine (ExoPlayer/Media3) on TV hardware**.

### Root cause
ExoPlayer/Media3's default renderers do **not** include software decoders for AC3/E-AC3/Atmos. Most Android TV / Fire Stick devices also lack hardware Dolby decoders or HDMI passthrough for these codecs (unlike phones, which usually have hardware AC3/EAC3 decode paths, or unlike LibVLC, which bundles FFmpeg-based software decoders for these codecs internally).

- When no manual track is selected, ExoPlayer silently drops the unsupported audio track and only renders video → silent playback.
- When the track is manually forced, ExoPlayer tries to initialize a decoder for a codec it can't handle → playback stalls/freezes.

## Solution

Implement one or both of the following in the ExoPlayer/Media3 playback module:

### 1. Add the Media3 FFmpeg audio decoder extension (primary fix)
- Build the `media3-decoder-ffmpeg` extension from the AndroidX Media GitHub repo (`extensions/ffmpeg`) using the NDK, since it isn't published as a plain Maven artifact.
- Add it as a dependency alongside `media3-exoplayer`.
- In `DefaultRenderersFactory`, set:
  ```kotlin
  val renderersFactory = DefaultRenderersFactory(context)
      .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
  ```
  `PREFER` makes ExoPlayer prioritize the FFmpeg software decoder over hardware decoders for codecs the hardware doesn't support, enabling AC3/E-AC3/Atmos tracks to decode entirely in software — matching what LibVLC already does internally.

### 2. Automatic engine fallback (safety net / simpler alternative)
- Detect the audio codec `MimeType` of the selected media item (e.g., `audio/ac3`, `audio/eac3`) before/at playback start, or catch ExoPlayer's decoder-initialization failure/exception.
- If the codec is one ExoPlayer can't handle on the current device, automatically switch that specific item to the **LibVLC engine** (already present in the codebase from v1) instead of ExoPlayer.
- Keep ExoPlayer as the default for everything else (AAC/MP2/H264 content, live TV) to preserve its performance/battery benefits, using LibVLC only as a targeted fallback for problematic codecs.

### Recommended approach
Do **both**: add the FFmpeg extension for a permanent native fix, and keep the LibVLC fallback as a safety net for any device/codec combination the FFmpeg extension still can't handle (e.g., some DTS variants).

## Acceptance Criteria
- Movie files with DD+/Atmos/AC3 audio play with audio automatically (no manual track selection needed) on Android TV/Firestick.
- Manually selecting an audio track for such files no longer causes freezing/buffering.
- Live TV playback and mobile playback remain unaffected/working as before.
- No regression in playback performance for standard AAC/H264 content.
