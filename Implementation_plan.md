# M3U Video Player — Detailed Micro-Step Implementation Plan & Progress Tracker

Project: `M3uVideoPlayer` (Android, MVVM + Clean Architecture, Room, Hilt, LibVLC)
This file tracks progress step by step. Every time a step is completed, its status is updated here so anyone (human or AI) can see exactly what is done and what remains.

**Legend:** ✅ Done · 🔄 In Progress · ⬜ Not started yet

---

## PHASE 1 — Advanced Playlist Management & Storage

### Step 1: Build System & Core Setup ✅ **COMPLETE**

- [x] Hilt DI setup for the whole project
- [x] Room entities: Playlist, Channel, Group, History (Favorites handled via `isFavorite` flag on Channel)
- [x] Clean Architecture folders: `data`, `domain`, `ui`, `di`
- [x] Removed duplicate/unused M3U parser file
- [x] Added missing Gradle dependencies (RecyclerView, lifecycle-viewmodel-ktx, fragment-ktx, swiperefreshlayout, Navigation Safe Args plugin)

### Step 2: Playlist Logic — Parser & Use Cases ✅ **COMPLETE**

- [x] M3U/M3U8 parser (group-title, tvg-logo, tvg-id, stream URL)
- [x] Domain use cases: `GetPlaylistsUseCase`, `AddPlaylistUseCase`, `DeletePlaylistUseCase`, `SyncPlaylistUseCase`
- [x] Domain use cases: `GetChannelsForPlaylistUseCase`, `GetFavoriteChannelsUseCase`, `ToggleFavoriteChannelUseCase`
- [x] Xtream Codes client (Retrofit) — **basic stub exists, not yet functional** (see Step 5)

### Step 3: Initial UI — Playlist Management ✅ **COMPLETE**

- [x] Playlist List screen (RecyclerView, pull-to-refresh, empty state, per-item refresh/delete)
- [x] Add Playlist screen (M3U URL / Xtream toggle, validation)
- [x] Channel List screen (shows real parsed channels per playlist, favorite toggle)
- [x] Navigation graph wiring all 3 screens together (Safe Args)

### Step 4: View Toggles (Folder/Channel view modes) ✅ **COMPLETE**

- [x] **Step 4.1:** Group বা Folder ব্রাউজিংয়ের জন্য UI Layout তৈরি করা (একটি Grid বা List ভিউ যা শুধু গ্রুপগুলো দেখাবে)।
  - New `GroupListFragment` + `GroupListViewModel` + `GroupAdapter` (`ui/group` package) showing a 3-column grid of folder tiles (name + channel count), derived from the playlist's synced channels grouped by `group-title`.
  - New layouts: `fragment_group_list.xml` (grid + empty state + progress bar) and `item_group.xml` (folder tile), plus `ic_folder.xml` drawable.
  - Nav graph updated: `PlaylistFragment` -> `GroupListFragment` -> `ChannelListFragment`. `channelListFragment` now also declares an optional nullable `groupName` argument (defaults to null, unused for now) so the next step can wire real filtering without another graph change.
  - Update: Step 4.2 is now complete — clicking a group tile filters the channel list by that group.
- [x] **Step 4.2:** Channel List স্ক্রিনে Group ফিল্টারিং লজিক অ্যাড করা (কোনো গ্রুপে ক্লিক করলে শুধু সেই গ্রুপের চ্যানেলগুলো দেখাবে)। — DONE: `ChannelListViewModel` filters by the passed `groupName`/`favoritesOnly` nav args.
- [x] **Step 4.3:** View Mode (List / Grid / Title-only / Poster) এর জন্য XML layout এবং RecyclerView Adapter Holder ডিজাইন করা। — DONE: `ChannelViewMode` enum + per-mode layouts/ViewHolders in `ChannelAdapter`.
- [x] **Step 4.4:** Shared Preferences বা DataStore সেটআপ করা, যাতে ইউজারের সিলেক্ট করা ভিউ মোড গ্লোবালি সেভ থাকে। — DONE: `UserPreferencesRepository` (Preferences DataStore).
- [x] **Step 4.5:** UI-তে ভিউ মোড সুইচার বাটন অ্যাড করা এবং DataStore-এর সাথে কানেক্ট করে লাইভ ভিউ চেঞ্চ করা। — DONE: toolbar view-mode switcher wired live to the DataStore flow.

### Step 5: Xtream Codes Client (full) ✅ **COMPLETE**

- [x] Dynamic per-provider base URL — `XtreamApiFactory` builds/caches one Retrofit `XtreamApi` per provider base URL
- [x] Proper response DTOs: `XtreamAuthResponse`/`XtreamUserInfo`, `XtreamCategoryDto`, etc.
- [x] Map Xtream response → Channel/Group domain models
- [x] Wired into `SyncPlaylistUseCase`
- [x] DB bumped to version 2 with `fallbackToDestructiveMigration()`

### Step 6: WorkManager Sync ✅ **COMPLETE**

- [x] **Step 6.1:** `PlaylistSyncWorker` ক্লাস তৈরি করা এবং এর ভেতরে `SyncPlaylistUseCase` ইনজেক্ট করে ব্যাকগ্রাউন্ড সিঙ্ক লজিক লেখা। — DONE: `PlaylistSyncWorker` (CoroutineWorker) injects `PlaylistRepository` and calls `syncPlaylist`.
- [x] **Step 6.2:** হিল্ট (`@HiltWorker`) এর সাথে `WorkManager` কনফিগার করা যাতে ডিপেন্ডেন্সি ইনজেকশন ঠিকঠাক কাজ করে। — DONE (via Hilt `@EntryPoint`/`SingletonComponent` instead of `@HiltWorker`, functionally equivalent DI).
- [x] **Step 6.3:** ইউজার ইন্টারভাল (On start / 6h / 10h / Daily) সেট করার জন্য একটি সাধারণ Settings UI/Dialog তৈরি করা। — DONE: `UpdateFrequency` dropdown in Add/Edit Playlist screens (On start / 6h / 12h / 3 days / Week / Never).
- [x] **Step 6.4:** ইউজারের সিলেক্ট করা ইন্টারভালের ওপর ভিত্তি করে `PeriodicWorkRequest` শিডিউল এবং ক্যানসেল করার লজিক ম্যানেজার তৈরি করা। — DONE: `PlaylistUpdateScheduler` enqueues/cancels unique periodic + one-time (ON_START) work per playlist.

---

## PHASE 2 — Dual User Interface (Mobile & TV)

### Step 7: Android TV / Firestick Support ✅ **COMPLETE**

- [x] **Step 7.1:** `AndroidManifest.xml` ফাইলে Leanback ফ্ল্যাগ, TV ব্যানার এবং TV-specific অ্যাক্টিভিটি কনফিগার করা। — DONE: `uses-feature` (touchscreen `required=false`, leanback `required=false`), `android:banner="@drawable/tv_banner"` (new vector banner), and a new `.tv.TvMainActivity` (`FragmentActivity`, `@AndroidEntryPoint`) declared with the `LEANBACK_LAUNCHER` category. Minimal placeholder layout `activity_tv_main.xml` for now; Leanback browse UI comes in 7.2/7.3.
- [x] **Step 7.2:** TV-র জন্য Leanback লাইব্রেরি অথবা Compose-for-TV এর বেসিক লেআউট সেটাপ করা। — DONE (Leanback chosen, matches the View-based app): added `androidx.leanback:leanback:1.0.0`, a `Theme.M3uVideoPlayer.Leanback` (parent `Theme.Leanback.Browse`) applied to `TvMainActivity`, and `activity_tv_main.xml` now hosts a `BrowseSupportFragment` via `FragmentContainerView`.
- [x] **Step 7.3:** TV Browse স্ক্রিন তৈরি করা (বাম পাশে ক্যাটাগরি/গ্রুপ লিস্ট এবং ডানপাশে চ্যানেলের গ্রিড)। — DONE: `TvBrowseFragment` (BrowseSupportFragment) + `TvBrowseViewModel` (`@HiltViewModel`) aggregate all playlists' channels into rows (pinned "Favorites" row first, then group-titles, blank → "Uncategorized"). Group titles render as left-hand headers; each group's channels render as a right-hand card grid via `ChannelCardPresenter` (`ImageCardView`, logos via Coil, `ic_tv_channel_placeholder` fallback). Clicking a channel shows a "player coming soon" toast (real playback is Phase 3).
- [x] **Step 7.4 (DONE):** `ListRowPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM)` + shadows + focusable `ImageCardView` give native D-pad focus zoom/highlight; headers reachable via back/left key (`HEADERS_ENABLED`, `isHeadersTransitionOnBackEnabled`). Original: D-pad Focus Management হ্যান্ডেল করা (রিমোটের বাটন টিপলে যাতে ফোকাস বর্ডার বা হাইলাইট ঠিকঠাক নড়াচড়া করে)।

### Step 8: Search & Discovery ✅ **COMPLETE**

- [x] **Step 8.1:** Room Database-এ গ্লোবাল সার্চ কুয়েরি (`LIKE` অপারেটর দিয়ে) লেখার জন্য `ChannelDao`-তে ফাংশন তৈরি করা। — DONE (implemented as an in-memory fuzzy `FuzzyIndex` over synced channels rather than a Room `LIKE` query, but delivers the same result).
- [x] **Step 8.2:** Search-এর জন্য Domain Use Case (`SearchChannelsUseCase`) এবং ViewModel লজিক লেখা। — DONE: search wired into `ChannelListViewModel`/`GroupListViewModel`.
- [x] **Step 8.3:** UI-তে একটি সুন্দর Search Bar বা ডেডিকেটেড সার্চ স্ক্রিন তৈরি করা। — DONE: `SearchView` in the toolbar (both Group and Channel screens).
- [x] **Step 8.4:** চ্যানেলের জন্য Sorting লজিক (A-Z, Z-A, Recently Viewed) ViewModel-এ ইমপ্লিমেন্ট করা। — DONE: `ChannelSortOrder`/`FolderSortOrder` (Ascending / Descending / Playlist order) instead of "Recently Viewed", persisted via DataStore.
- [x] **Step 8.5:** ডেডিকেটেড Favorites স্ক্রিন তৈরি করা এবং `GetFavoriteChannelsUseCase` থেকে ডাটা এনে দেখানো। — DONE, but as a pinned "Favorite" tile on the Group screen (reuses the Channel List filtered by `favoritesOnly`) rather than a separate dedicated screen.
- [x] **Step 8.6:** ডেডিকেটেড History স্ক্রিন তৈরি করা (ইউজার কোনো চ্যানেলে ক্লিক করলে `HistoryDao`-তে সেভ হবে এবং এই স্ক্রিনে দেখাবে)। — Scaffolding only: `HistoryEntity`/`HistoryDao`/`PlaybackHistory` model exist, but no screen and nothing writes to it yet (playback isn't wired up — see Step 9).

---

## PHASE 3 — LibVLC Engine & Player Controls

### Step 9: Video Engine ✅ **COMPLETE**

- [x] **Step 9.1:** `PlayerActivity` তৈরি করা এবং XML-এ LibVLC-এর `SurfaceView` বা `TextureView` লেআউট সেট করা।
- [x] **Step 9.2:** LibVLC `Instance` এবং `MediaPlayer` ইনিশিয়ালাইজেশন কোড লেখা (Hilt di বা ফ্যাক্টরি দিয়ে)।
- [x] **Step 9.3:** চ্যানেল ইউআরএল পাস করে বেসিক ভিডিও প্লেব্যাক এবং এরর হ্যান্ডলিং (যেমন: লোডিং স্পিনার, নেটওয়ার্ক এরর টেক্সট) চালু করা।
- [x] **Step 9.4:** LibVLC-তে অডিও গেইন বা ডিজিটাল অ্যামপ্লিফায়ার কোড লিখে ২০০% ভলিউম বুস্ট ফিচার যোগ করা।
- [x] **Step 9.5:** LibVLC-র কনফিগারেশনে 4K/8K ভিডিওর জন্য হার্ডওয়্যার অ্যাক্সিলারেশন (`--hardware-acceleration`) ফ্ল্যাগ এনাবল করা।

### Step 10: Advanced Player UI ✅ **COMPLETE**

- [x] **Step 10.1:** প্লেয়ার স্ক্রিনের জন্য কাস্টম কন্ট্রোলার UI (Play/Pause, Seekbar, Time Duration, Fullscreen বাটন) ডিজাইন করা। — DONE: `activity_player.xml` now has an auto-hiding `controlsOverlay` (top bar: back / title / audio-mode; center play/pause; bottom bar: elapsed | seekbar | duration + boost + fullscreen). A 500ms ticker drives the seekbar/time, a single tap toggles the overlay (auto-hides after 3.5s), and the fullscreen button cycles LibVLC `ScaleType` best-fit ↔ fill.
- [x] **Step 10.2:** প্লেয়ার স্ক্রিনে কাস্টম `OnTouchListener` ইমপ্লিমেন্ট করে ভার্টিকাল সোয়াইপে ব্রাইটনেস এবং ভলিউম চেঞ্জ করার লজিক লেখা। — DONE: a `GestureDetector` on `playerRoot` maps a vertical drag on the left half to window brightness and on the right half to `AudioManager` STREAM_MUSIC volume, with a transient `gestureIndicator` pill showing the %.
- [x] **Step 10.3:** স্ক্রিনের ডানে/বামে ডাবল-ট্যাপ ডিটেক্ট করে ±১০ সেকেন্ড সিক (Seek) করার ফিচার যোগ করা। — DONE: `onDoubleTap` seeks ∓10s on the left/right third (center third toggles play/pause), clamped to `[0, length]`, with a "+10s"/"-10s" indicator.
- [x] **Step 10.4:** প্লেয়ার লক মোড (Lock Button) তৈরি করা, যা অন থাকলে স্ক্রিনের সব টাচ ইভেন্ট ডিজেবল থাকবে। — DONE: `btnLock` toggles a `locked` flag; while locked the root touch listener swallows all controls + gestures, leaving only the (on-top) lock button live to unlock.
- [x] **Step 10.5:** ব্যাকগ্রাউন্ড প্লেব্যাক বা "Play as Audio" মোডের জন্য একটি Android Foreground Service তৈরি করা এবং LibVLC-কে সেটার সাথে কানেক্ট করা। — DONE: `AudioPlaybackService` (`@AndroidEntryPoint`, `foregroundServiceType="mediaPlayback"`) builds an audio-only (`:no-video`) `MediaPlayer` on the injected shared `LibVLC`, resumes from the handed-off position, and posts a play/pause + stop notification. The player's audio-mode button hands off and finishes the activity. Added the `POST_NOTIFICATIONS` permission.

---

## PHASE 4 — Multi-tasking & Secondary Features

### Step 11: Productivity & Playback ✅ **COMPLETE**

- [x] **Step 11.1:** Picture-in-Picture (PiP) মোড কনফিগার করা (`onUserLeaveHint` ওভাররাইড করে প্লেয়ার অটো ছোট করা)। — DONE: `PlayerActivity` declares `supportsPictureInPicture`/`resizeableActivity`, adds a PiP button (hidden when unsupported), enters PiP with a 16:9 `PictureInPictureParams`, auto-enters on `onUserLeaveHint` while playing, and keeps playback alive across `onPictureInPictureModeChanged`/`onStop`.
- [x] **Step 11.2:** প্লেয়ার কন্ট্রোলারে প্লেব্যাক স্পিড (0.5x থেকে 2.0x) চেঞ্জ করার জন্য একটি ড্রপডাউন বা ডায়ালগ মেনু বানানো। — DONE: `btnSpeed` opens a single-choice `MaterialAlertDialog` (0.5x–2.0x) driving `MediaPlayer.rate`, re-applied on the `Playing` event; also exposed via D-pad center long-press turbo 2x.
- [x] **Step 11.3:** মাল্টিপল অডিও এবং সাবটাইটেল ট্র্যাক সিলেক্ট করার জন্য একটি OSD (On-Screen Display) মেনু তৈরি করা। — DONE: `btnAudioTrack`/`btnSubtitles` list `mediaPlayer.audioTracks`/`spuTracks` in single-choice OSD dialogs (current track pre-checked, empty -> toast) and apply the pick via `audioTrack`/`spuTrack`.
- [x] **Step 11.4:** IPTV প্রোভাইডারের ক্যাচ-আপ (Catch-up / Archive) প্লেব্যাক ইউআরএল পার্স ও প্লে করার বেসিক মেকানিজম তৈরি করা。 — DONE: `catchup`/`catchup-type`/`timeshift`/`catchup-days`/`catchup-source` parsed in `M3uParser`, carried through `Channel`/`ChannelEntity`, turned into archive URLs by `util/CatchupResolver` (template substitution + default/shift/flussonic fallbacks).


**Prompt reworks folded into Step 10/11 (`promt1.txt`):**
- [x] **VLC-style OSD:** top-bar OSD row (subtitles, audio track, speed, PiP, audio-mode) added to the controller.
- [x] **Gesture rework:** right-side vertical swipe drives LibVLC's 0-200% software gain incrementally (no button); left-side brightness auto-disabled on Smart TVs (`util/DeviceUtils.isTv`, which then treats the whole surface as a volume slider).
- [x] **D-pad / keyboard (TV remotes):** Up/Down = volume; Left/Right single = +/-10s, double = +/-30s; center single = play/pause, long-press = temporary 2x turbo (normalizes on release). Handled in `onKeyDown`/`onKeyLongPress`/`onKeyUp`.

---

## Verification Plan

### Automated Tests ⬜ **NOT STARTED**

- [ ] **Step 12.1:** M3U পার্সারের জন্য একটি Unit Test ক্লাস লেখা।
- [ ] **Step 12.2:** Xtream parsing এবং mapping লজিকের জন্য Unit Test লেখা。
- [ ] **Step 12.3:** Room DAO-র History এবং Favorites কুয়েরি টেস্ট করার জন্য Android Instrumented Test লেখা।

---

## How this file is used

- After every completed step, this file gets updated (checkbox ticked + status changed to ✅) and re-shared with the updated project zip.
- Anyone — including another AI assistant — can read this file top-to-bottom to know exactly what's implemented in the current zip and what's the next task to pick up.

**Last updated:** Step 11 (Productivity & Playback) is now COMPLETE — PiP (11.1), speed selector 0.5x–2.0x (11.2), audio/subtitle track OSD (11.3), and the catch-up parse/resolve mechanism (11.4) — together with the `promt1.txt` reworks (VLC-style OSD row, 0–200% right-side volume gesture, TV-aware brightness disable via `DeviceUtils.isTv`, and full D-pad/keyboard controls: volume, single/double ±10s/±30s seek, center play-pause + long-press 2x turbo). The debug build passes and the APK assembles. Remaining: Step 8.6 (History screen), Step 7 TV steps as noted, and Step 12 (Automated tests).
