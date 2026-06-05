# PULLOUT Android — Design Spec
**Date:** 2026-06-05
**Status:** Approved
**Project root:** `D:\Projects\PULLOUT`
**GitHub:** `Andro-Meta/pullout-android`
**Package:** `com.andrometa.pullout`

---

## Overview

PULLOUT is a fully self-contained Android media downloader. No browser wrapper, no
external server, no setup. A patched build of cobalt's extraction engine (20+ services:
YouTube, TikTok, Instagram, Twitter/X, Reddit, SoundCloud, Vimeo, Twitch, and more) runs
as a local HTTP server via nodejs-mobile-android. The native Android UI carries Andro.Meta's
cyber-retro-futuristic design language. All muxing (video+audio stream merging) is done
on-device via ffmpeg-kit-android.

Previous version (cobalt-android WebView wrapper) was archived; this replaces it entirely.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  PULLOUT Native UI  (Kotlin · Andro.Meta design)            │
│  MainActivity · PickerSheet · QueueSheet · SettingsSheet    │
└───────────────────────────┬─────────────────────────────────┘
                            │  HTTP  localhost:9000
┌───────────────────────────▼─────────────────────────────────┐
│  CobaltServerService  (foreground · nodejs-mobile)          │
│  Patched cobalt API — 20+ services · no ffmpeg dependency   │
│  Returns localProcessing responses for muxable content      │
└───────────────────────────┬─────────────────────────────────┘
       tunnel/redirect      │         local-processing
       ┌───────────────────┐│┌────────────────────────────────┐
       │  OkHttp download  │││  LocalMuxingManager            │
       │  → MediaStore     │││  ffmpeg-kit temp merge         │
       └───────────────────┘│└────────────────────────────────┘
                            │
              DownloadService (foreground · dataSync)
              Room · WorkManager · NotificationHelper
```

### Components

| Component | Role |
|---|---|
| `CobaltServerService` | Foreground service; copies nodejs-project to filesDir on first launch; starts Node.js via nodejs-mobile; health-polls localhost:9000; exposes `ServerState` LiveData |
| `NodeServerManager` | Singleton coordinating server lifecycle; retries on crash; exposes cold/warming/ready/error state |
| `CobaltApiClient` | OkHttp wrapper; POST `localhost:9000` with `localProcessing:"forced"`; parses typed response sealed class |
| `LocalMuxingManager` | ffmpeg-kit-android; downloads video+audio tunnels to cache; muxes; writes to MediaStore; cleans up temp files |
| `DownloadService` | Foreground (dataSync); routes CobaltApiClient responses to OkHttp download OR LocalMuxingManager; updates Room; notifies |
| `PickerSheet` | Bottom sheet Fragment; shows thumbnail grid for multi-item cobalt responses (Instagram, Twitter slideshows) |
| `DownloadQueueSheet` | Bottom sheet Fragment; active downloads + history tabs; PULLOUT-styled |
| `SettingsSheet` | Bottom sheet Fragment; quality prefs, audio-only, cookies note |
| `MainActivity` | Single Activity; server status gate; URL input; clipboard/share handling; FAB queue |
| Room/WorkManager stack | DownloadRecord, DownloadDao, DownloadDatabase, DownloadRepository, RetryDownloadWorker — carried forward from v1 with minor updates |
| `MediaStoreWriter` | Unchanged from v1 |

### Cobalt Patches (2 files)

**`api/src/processing/services/youtube.js`** — replace `isolated-vm` sandbox eval with
`new Function()`. `isolated-vm` is a C++ native module incompatible with Android. For a
local personal-device app the sandboxing is unnecessary:
```javascript
// BEFORE (isolated-vm):
import ivm from "isolated-vm";
Platform.shim.eval = async (data) => {
  const isolate = new ivm.Isolate();
  const context = await isolate.createContext();
  return await isolate.compileScript(...).run(context, ...);
};

// AFTER (new Function — 5 lines):
Platform.shim.eval = async (data) => {
  return new Function(`"use strict"; return ((() => { ${data.output} })())`)();
};
```

**`api/package.json`** — remove `isolated-vm` and `ffmpeg-static` from dependencies.
cobalt's `localProcessing:"forced"` mode never calls ffmpeg; the Android layer handles
muxing via ffmpeg-kit.

### nodejs-mobile Integration

- Library: `nodejs-mobile-android` release AAR from https://github.com/nodejs-mobile/nodejs-mobile-android/releases
- Node.js version: 18.20.4 (bundled in AAR)
- nodejs-project placed in `app/src/main/assets/nodejs-project/`
- On first launch: `NodeServerManager` copies assets to `context.filesDir/nodejs-project/`
- Start: `NodeJsMobile.startNodeWithArguments(arrayOf("node", "main.js"), filesDir)`
- No IPC bridge needed — cobalt API is a plain HTTP server on port 9000

### ffmpeg-kit Integration

- Dependency: `com.arthenica:ffmpeg-kit-android-min:6.0`
- Covers: remux (copy codecs), MP3/Opus audio conversion
- Used only for `local-processing` responses (YouTube 1080p+, some others)
- Never called from Node.js; called from `LocalMuxingManager` in Android layer

---

## PULLOUT Design System

Cyber-retro-futuristic. Sharp angles, neon accents, monospace terminal font, dark void
backgrounds. Influenced by 80s/90s sci-fi interfaces and cyberpunk aesthetics.

### Color Tokens

```
background:          #060608   // Near-pure black, faint blue cast
surface:             #0E0E16   // Main card/container
surface-elevated:    #161624   // Sheets, dialogs
surface-hover:       #1C1C2E   // Interactive hover

neon-cyan:           #00E5FF   // Primary accent
neon-cyan-dim:       #0099BB   // Inactive/secondary cyan
neon-magenta:        #C855F0   // Secondary accent
neon-green:          #39FF14   // Success / active / complete
neon-red:            #FF2244   // Error / destructive
neon-amber:          #FFB300   // Queued / warning

text-primary:        #D8E4FF   // Main text — cool blue-white
text-secondary:      #3A4870   // Muted labels
text-dim:            #1A2040   // Disabled / placeholder

border:              #0A1030   // Hairline borders
border-active:       rgba(0,229,255,0.25)  // Focused/active element borders
glow-cyan:           rgba(0,229,255,0.10)  // Neon glow fill backgrounds
scanline-overlay:    rgba(0,0,20,0.04)     // Alternating scanline texture
```

### Typography

Font: **Space Mono** (Google Fonts downloadable, no bundling needed)
- Regular (400): body, labels, status text
- Bold (700): wordmarks, headings, button labels

All UI text is monospace. Sizes mirror cobalt's 14.5sp body / 12sp secondary pattern.

### Visual Signature Elements

- **Dot-grid background** on main screen (4dp spaced cyan dots, 3% opacity)
- **Scanline overlay** on surfaces (hairline stripes every 3dp, 4% opacity)
- **Neon glow** on focused inputs and active states (0dp blur, 6dp spread, cyan/12%)
- **Sharp corners** everywhere — border-radius: 3dp max
- **Animated pulse** on the server status indicator while warming up
- **Glitch flash** on error states (single-frame color shift animation)
- **Progress bars** filled neon-cyan with a trailing glow

### Screens

**Main Screen (InputScreen):**
- Full-screen dark background with dot-grid texture
- `PULLOUT` wordmark — Space Mono Bold, large, neon-cyan
- Server status chip below wordmark:
  - `▓ INITIALIZING...` (amber, pulsing) while server boots
  - `▓ READY` (green) when healthy
  - `▓ OFFLINE` (red) on failure
- URL input field: full-width, monospace, neon-cyan border when focused, cyan cursor blink
- `[ PULL ]` button: full-width, neon-cyan border, text fills cyan on tap
- Active download count badge on queue FAB (bottom-right)
- `[ ⋯ ]` settings trigger top-right

**Download Queue Sheet:**
- `#0E0E16` surface, drag handle
- Active / History tabs in Space Mono
- Each row: filename (primary), service + size + status (muted), neon-cyan progress bar
- Completed: neon-green check, `[ OPEN ]` `[ SHARE ]` buttons
- Failed: neon-red stripe, `[ RETRY ]` button

**Picker Sheet:**
- Thumbnail grid (2-column) with neon-cyan selection outlines
- `[ PULL ALL ]` and `[ PULL SELECTED ]` buttons

**Settings Sheet:**
- Audio-only mode toggle
- Default quality selector (Max / 1080 / 720 / 480)
- Cookies info note (links to Chrome Custom Tab with cobalt docs)
- Clear history

---

## Data Flows

### Flow A — Server startup
1. `CobaltApplication.onCreate()` binds/starts `CobaltServerService`
2. Service checks if `filesDir/nodejs-project/` exists; copies from assets if not (first launch ~3-5s)
3. `NodeJsMobile.startNodeWithArguments(["node","main.js"], filesDir)` — runs on background thread
4. Health coroutine polls `GET localhost:9000` every 500ms, timeout 30s
5. On first 200 response: `ServerState.READY` posted to LiveData
6. `MainActivity` observes LiveData; unblocks UI

### Flow B — URL submission
1. URL arrives via: text input, clipboard snackbar, share intent (`ACTION_SEND`), or home shortcut
2. `UrlMatcher.extractUrl()` finds first supported URL in text
3. UI shows `PULLING...` loading state
4. `CobaltApiClient.submit(url)`:
   ```
   POST localhost:9000
   {"url":"...", "localProcessing":"forced", "videoQuality":"1080", "downloadMode":"auto"}
   ```
5. Parse response → sealed class `CobaltResponse`

### Flow C — Direct download (`tunnel` / `redirect`)
1. `CobaltResponse.Tunnel(url, filename, mimeType)`
2. `DownloadService` queues `DownloadRecord(status=QUEUED)`
3. OkHttp streams URL → `MediaStoreWriter.open()` → progress updates Room every 500ms
4. On complete: `mediaStoreUri` saved, `status=COMPLETE`, notification
5. On network failure: `status=FAILED_NETWORK`, `WorkManager` retry (max 3, 30s backoff)

### Flow D — Local processing (`local-processing`)
1. `CobaltResponse.LocalProcessing(type, tunnels[], output, audio?)`
2. `DownloadService` creates `DownloadRecord(status=QUEUED, isMuxed=true)`
3. `LocalMuxingManager.process(record, response)`:
   a. Download `tunnels[0]` → `cacheDir/pullout_v_{id}.tmp` (video)
   b. Download `tunnels[1]` → `cacheDir/pullout_a_{id}.tmp` (audio, if merge type)
   c. `FFmpegKit.executeAsync("-i video.tmp -i audio.tmp -c:v copy -c:a copy output.tmp")`
   d. On success: `MediaStoreWriter.open(filename, mimeType)` → write output → `finalize()`
   e. Delete all temp files (in finally block)
4. Progress: three phases visible in queue — Download Video / Download Audio / Merging

### Flow E — Picker response
1. `CobaltResponse.Picker(items[], backgroundAudio?)`
2. `PickerSheet.show()` with thumbnail grid
3. User selects items → each triggers Flow C or D independently
4. All queued items appear in queue sheet

### Flow F — Error response
1. `CobaltResponse.Error(code, context?)`
2. Show inline error banner below input: `cobalt.error.{code}` in Space Mono
3. Specific codes translated: `noFiles` → "nothing found at that URL" etc.

---

## Error Handling

| Error | Owner | User sees |
|---|---|---|
| Server fails to start (timeout 30s) | `NodeServerManager` | Full-screen error: `PULLOUT OFFLINE` with `[ RETRY ]` — restarts service |
| Server crashes mid-session | `NodeServerManager` health monitor | Status chip switches to red, auto-restart attempted, UI shows `RECONNECTING...` |
| cobalt API error response | `CobaltApiClient` | Inline banner below input, error code + plain-English translation |
| Download network failure | `DownloadService` | Queue row red + `[RETRY]`; `WorkManager` auto-retry on reconnect |
| Download server error (4xx/5xx) | `DownloadService` | Queue row red + `[RETRY]`; no auto-retry |
| Mux failure (ffmpeg-kit) | `LocalMuxingManager` | Queue row red + toast "merge failed"; all temp files cleaned up in finally |
| Storage full (ENOSPC) | `DownloadService` | Persistent notification "not enough storage"; no retry |
| Notification permission denied | First-launch flow | Silent degradation — queue sheet remains source of truth |
| Battery optimization active | First-launch prompt | One-time dialog → battery settings |
| No supported URL | `UrlMatcher` | Snackbar: "no supported link found" |

---

## Permissions

```xml
<!-- Network -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Background services -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Notifications (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Battery optimization exemption -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- Storage — write via MediaStore only; no read perms needed for our own files -->
```

Runtime prompts:
1. `POST_NOTIFICATIONS` — requested on first launch (Android 13+)
2. Battery optimization — one-time dialog on first launch

### New Browser Windows

Chrome Custom Tabs (androidx.browser) handles all external URL opens:
- Cobalt documentation links in settings
- Any OAuth / cookie-setup flow a service requires
- External content links

```kotlin
CustomTabsIntent.Builder()
    .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
    .setNavigationBarColor(Color.parseColor("#060608"))
    .setToolbarColor(Color.parseColor("#0E0E16"))
    .build()
    .launchUrl(context, uri)
```

No WebView in the app at all. Chrome Custom Tabs gives users a full browser with back navigation and session cookies.

---

## Cobalt Source Bundling

Setup script (`scripts/setup-cobalt.ps1` / `scripts/setup-cobalt.sh`) runs once before Android build:

```
1. Clone imputnet/cobalt at pinned commit (or latest main)
2. Apply patch: cobalt/api/src/processing/services/youtube.js
3. Apply patch: cobalt/api/package.json (remove isolated-vm + ffmpeg-static)
4. cd cobalt/api && npm install --production
5. Copy cobalt/api/ → app/src/main/assets/nodejs-project/
6. Add .gitignore entry for nodejs-project/ (node_modules too large to commit)
```

`main.js` (entry point, placed in nodejs-project root):
```javascript
import dotenv from 'dotenv';
dotenv.config();
process.env.API_URL = process.env.API_URL || 'http://localhost:9000';
process.env.API_PORT = process.env.PORT || '9000';
import('./src/cobalt.js'); // cobalt's own entry point
```

Environment variables injected by `CobaltServerService` before starting Node:
```
API_URL=http://localhost:9000
API_PORT=9000
API_LISTEN_ADDRESS=127.0.0.1
```

---

## Known Limitations

- **First-launch asset copy**: ~3-5s delay while nodejs-project is copied from APK assets to filesDir. Shown as `INITIALIZING...` state. One-time only.
- **Server startup**: ~2-4s for Node.js to boot cobalt's express server. Shown as `INITIALIZING...`
- **APK size**: ~55-65MB (nodejs-mobile ~30MB, cobalt bundle ~10MB, ffmpeg-kit-min ~15MB)
- **`localProcessing:"forced"`**: All YouTube downloads go through the merge flow. Slower than direct downloads but required since we removed ffmpeg from Node.js layer.
- **Cookies**: Age-restricted YouTube and some other services require cobalt's `cookies.json`. v1 does not include a cookie setup flow. Chrome Custom Tabs can be used to export cookies manually.

---

## Tech Stack

Kotlin 1.9.23 · AGP 8.5.2 · Gradle 8.7 · KSP 1.9.23-1.0.20
Room 2.6.1 · WorkManager 2.9.0 · OkHttp 4.12.0 · Coroutines 1.8.1
Material 3 1.12.0 · View Binding · androidx.browser (Chrome Custom Tabs)
nodejs-mobile-android (Node 18.20.4 AAR)
ffmpeg-kit-android-min 6.0
Space Mono (Google Fonts downloadable)
Min SDK: 26 (Android 8.0) · Target SDK: 35

### DownloadRecord additions vs v1
```kotlin
val isMuxed: Boolean = false         // true for local-processing downloads
val muxPhase: String = ""            // "DOWNLOADING_VIDEO" | "DOWNLOADING_AUDIO" | "MERGING" | ""
```
Queue sheet shows three progress phases for muxed downloads.
