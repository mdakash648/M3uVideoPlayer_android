# Prompt: Fix Playlist Auto-Update Failing Due to Incorrect Device Time

## Context
This is an Android IPTV/M3U player app (`M3uVideoPlayer_android`) that supports **Background Sync** of playlists using WorkManager, with configurable intervals (On start, 6h, 12h, etc.).

## Problem

Playlist background/auto-update does not run correctly on some devices — especially **Android TV / Firestick boxes**, and occasionally mobile devices — because the app currently relies on the **device's system clock** to schedule and evaluate update intervals.

- Many TV boxes/Firesticks have incorrect system date/time set (wrong timezone, clock drift, or time never synced because the device has no SIM/GPS and weak/no NTP sync).
- WorkManager's periodic scheduling and the app's own "last updated" / "next update due" calculations are based on `System.currentTimeMillis()` / device wall-clock time.
- When the device clock is wrong (e.g., set to a past date, wrong timezone, or drifted), the app either:
  - thinks the last update was "in the future" and never triggers a new sync, or
  - miscalculates the interval and skips/delays updates indefinitely.
- This makes playlist refresh unreliable and inconsistent between devices, purely because of device clock/timezone misconfiguration — not app logic.

## Solution

Make the app's playlist-update scheduling **independent of the device's system clock**, and give the user explicit control over timezone handling.

### 1. Use the app's own reliable time source instead of device time
- Fetch current time from a **trusted network time source** (e.g., an NTP client library, or a lightweight authoritative time API/HTTP `Date` header from a reliable endpoint) instead of trusting `System.currentTimeMillis()` directly for scheduling decisions.
- Cache the last known-good network time along with the device's monotonic clock (`SystemClock.elapsedRealtime()`) at the moment of fetch, so the app can compute "how much time has actually passed" using the **monotonic clock** (which is not affected by the user/device changing wall-clock time) offset by the verified network time — rather than repeatedly trusting wall-clock time that could jump or be wrong.
- Persist this "trusted time" reference (e.g., in DataStore/Room) so the app can still function offline, falling back gracefully to device time only if network time is unavailable, with a flag/log indicating "using unverified device time."
- Use this internal trusted time for:
  - Calculating "time since last playlist update"
  - Deciding whether a scheduled update is due
  - Any timestamps shown in-app (e.g., "last synced at")

### 2. Add a Timezone system (Auto + Manual)
- **Auto mode (default):** Detect timezone automatically from the network/trusted time source (or device timezone only as a secondary signal, not the deciding factor for scheduling math — scheduling should be based on elapsed real duration, not on local calendar time).
- **Manual mode:** Add a settings option letting the user pick their timezone explicitly from a list, overriding auto-detection — useful for TV boxes with no reliable locale/timezone data, or users who want update timing to align with a specific region's schedule.
- Show the selected/detected timezone clearly in Settings (e.g., "Time Zone: Asia/Dhaka (Auto)" or "Time Zone: Asia/Dhaka (Manual)").
- Timezone affects only **display** of times (e.g., "last updated at 10:30 PM") and any user-facing scheduling options (e.g., "update playlist daily at 6:00 AM local time") — the underlying interval/due-date math should still be based on the trusted elapsed-time mechanism from Solution #1, not raw device local time, to avoid DST/timezone-change edge cases breaking the schedule.

### 3. Fallback & resilience
- If network time fetch fails (no internet at that moment), don't block scheduling — use the last cached trusted time + elapsed monotonic clock to keep functioning correctly until the next successful network time sync.
- Log/flag when the app is operating on unverified time, for debugging purposes.

## Acceptance Criteria
- Playlist auto-update triggers reliably at the configured interval regardless of whether the device's system clock/timezone is wrong.
- App has a Settings option to view current detected timezone and switch between Auto and Manual timezone modes.
- Manually setting an incorrect device date/timezone (test case) no longer breaks scheduled playlist updates.
- Update scheduling continues working correctly across app restarts and after periods offline.
- No regression: manual "refresh now" and other existing sync triggers still work as before.
