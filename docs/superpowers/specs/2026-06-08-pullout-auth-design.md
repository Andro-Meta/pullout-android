# PULLOUT Authentication (Cookies + YouTube po_token) — Design Spec
**Date:** 2026-06-08
**Status:** Approved
**Project root:** `D:\Projects\PULLOUT`
**Package:** `com.andrometa.pullout`

---

## Overview

Add account authentication to PULLOUT so auth-gated services work: YouTube (full
quality via po_token), Instagram, Twitter/X, and Reddit. The design follows
cobalt's **official mechanism** exactly:

- **Cookies** — captured via an in-app login WebView, written to cobalt's
  `cookies.json`, fed via the `COOKIE_PATH` env var.
- **YouTube po_token** — a faithful on-device port of cobalt's
  [yt-session-generator](https://github.com/imputnet/yt-session-generator):
  a hidden WebView loads a YouTube embed, YouTube's own page generates the token
  during playback, and we intercept the `/youtubei/v1/player` request to harvest
  `{po_token, visitor_data}`. Served over HTTP at `POST /get_pot`, consumed by
  cobalt via the `YOUTUBE_SESSION_SERVER` env var.

Verified contract against cobalt `main` (2026-04): `youtube-session.js` POSTs to
`{YOUTUBE_SESSION_SERVER}/get_pot` and expects `{potoken, visitor_data, updated}`
(po_token ≥160 chars); `COOKIE_PATH` points at `cookies.json`;
`YOUTUBE_SESSION_INNERTUBE_CLIENT=WEB_EMBEDDED` selects the compatible client.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  PULLOUT UI — Settings → AccountsSheet                        │
│  [ youtube ✓ ] [ instagram ] [ x/twitter ] [ reddit ]        │
└───────────────┬─────────────────────────┬────────────────────┘
                │ login                    │ (youtube only)
     ┌──────────▼──────────┐   ┌───────────▼─────────────────┐
     │  LoginActivity      │   │  PoTokenWebView (hidden)    │
     │  CookieManager read │   │  YouTube embed + fetch-wrap │
     └──────────┬──────────┘   │  → {po_token, visitor_data} │
                │              └───────────┬─────────────────┘
     ┌──────────▼──────────┐   ┌────────────▼────────────────┐
     │  CookieStore        │   │  PoTokenServer (NanoHTTPD)  │
     │  → filesDir/        │   │  POST /get_pot              │
     │    cookies.json     │   │  on 127.0.0.1:9001          │
     └──────────┬──────────┘   └────────────┬────────────────┘
                │ COOKIE_PATH                │ YOUTUBE_SESSION_SERVER
                └─────────────┬──────────────┘
                ┌─────────────▼──────────────┐
                │  CobaltServerService        │
                │  injects env vars,          │
                │  restarts node on change    │
                └─────────────────────────────┘
```

### Components

| Component | Role |
|---|---|
| `LoginActivity` + `activity_login.xml` | Full-screen Chromium WebView; loads a service login URL; detects auth-cookie presence; PULLOUT-themed chrome |
| `CookieStore` | Reads `CookieManager.getCookie(domain)`, formats into cobalt `cookies.json` shape, persists to `filesDir/cookies.json`; exposes which services are logged in |
| `PoTokenWebView` | Hidden `WebView`; loads YouTube embed; injects `potoken_capture.js` (fetch/XHR wrapper); harvests `{po_token, visitor_data}`; auto-refreshes |
| `PoTokenServer` | `NanoHTTPD` on `127.0.0.1:9001`; serves `POST /get_pot` → `{potoken, visitor_data, updated}`; 503 until first token ready |
| `PoTokenManager` | Singleton coordinating PoTokenWebView + PoTokenServer lifecycle; refresh scheduling; exposes token state |
| `AccountsSheet` + `sheet_accounts.xml` | Bottom sheet listing services with login/logout buttons and status dots |
| `AccountStatus` | Data model: per-service `{service, loggedIn, lastUpdated}` |
| `CobaltServerService` (updated) | Injects `COOKIE_PATH`, `YOUTUBE_SESSION_SERVER`, `YOUTUBE_SESSION_INNERTUBE_CLIENT`; restarts node when cookies change |
| `NodeServerManager` (updated) | `restartServer()` re-reads cookies (cobalt loads them at startup only) |

### New dependency
`org.nanohttpd:nanohttpd:2.3.1` (~50KB) for the local `/get_pot` endpoint.

---

## Cookie Format

cobalt `cookies.json` — top-level service keys, each an array of
`key=value; key2=value2` strings:

```json
{
  "youtube":   ["SID=...; HSID=...; SSID=...; APISID=...; SAPISID=...; __Secure-1PSID=..."],
  "instagram": ["mid=...; ig_did=...; csrftoken=...; ds_user_id=...; sessionid=..."],
  "twitter":   ["auth_token=...; ct0=..."],
  "reddit":    ["client_id=...; client_secret=...; refresh_token=..."]
}
```

### Service → login URL + required-cookie detection

| Service | Login URL | Logged-in when these cookies present |
|---|---|---|
| youtube | `https://accounts.google.com/ServiceLogin?service=youtube` | `SID` AND `SAPISID` (or `__Secure-1PSID`) |
| instagram | `https://www.instagram.com/accounts/login/` | `sessionid` AND `ds_user_id` |
| twitter | `https://x.com/login` | `auth_token` AND `ct0` |
| reddit | `https://www.reddit.com/login` | `reddit_session` (mapped — see note) |

`CookieStore` reads `CookieManager.getInstance().getCookie(domain)` for the
service domain, filters to the relevant keys, and joins as `k=v; k=v`. For
YouTube it reads both `.google.com` and `.youtube.com` domains and merges.

> Reddit note: cobalt's reddit cookie expects `client_id/client_secret/refresh_token`
> (OAuth app creds), which a website login does not provide. For v1, Reddit login
> captures the standard session cookie which covers most public content; full OAuth
> is out of scope and flagged in the UI as "basic".

---

## Data Flows

### Flow A — Cookie login
1. User taps a service in `AccountsSheet` → `LoginActivity` opens that service's login URL.
2. User logs in normally inside the WebView.
3. After each page load, `LoginActivity` checks `CookieManager` for the service's
   required cookies. When present → success.
4. `CookieStore.saveService(service, cookieString)` merges into `filesDir/cookies.json`.
5. `LoginActivity` returns RESULT_OK; `AccountsSheet` shows the service as logged in.
6. PULLOUT calls `NodeServerManager.restartServer()` so cobalt reloads cookies
   (cobalt reads `cookies.json` only at startup).

### Flow B — po_token generation (YouTube)
1. On server start (or when YouTube is logged in), `PoTokenManager.start()`:
   a. `PoTokenServer` (NanoHTTPD) starts on `127.0.0.1:9001`.
   b. `PoTokenWebView` (hidden, attached to the app window via a 0-size view)
      loads `https://www.youtube.com/embed/jNQXAC9IVRw` (a stable public video).
   c. `potoken_capture.js` is injected at document-start: wraps `window.fetch`
      and `XMLHttpRequest.prototype.send` to capture any request body sent to a
      URL containing `/youtubei/v1/player`.
   d. JS auto-clicks `#movie_player` / sets `video.play()` to trigger the player request.
   e. On capture, JS parses the body and extracts
      `context.client.visitorData` and `serviceIntegrityDimensions.poToken`,
      then calls `PoTokenBridge.onToken(potoken, visitorData)`.
2. `PoTokenManager` stores `{potoken, visitor_data, updated}` and `PoTokenServer`
   serves it.
3. cobalt's `youtube-session.js` POSTs `/get_pot` on its reload interval and
   pulls the token automatically.
4. Refresh: `PoTokenManager` reloads the WebView every 30 min (configurable) to
   keep the token fresh; on failure it retries with backoff.

### Flow C — Env var injection
`CobaltServerService`, before `NodeJsMobile.startNodeWithArguments`, sets process
env (via the JNI bridge's `setenv` or by writing a `.env` file in the project dir):
```
COOKIE_PATH=<filesDir>/nodejs-project/cookies.json
YOUTUBE_SESSION_SERVER=http://127.0.0.1:9001/
YOUTUBE_SESSION_INNERTUBE_CLIENT=WEB_EMBEDDED
```
Because nodejs-mobile's `node::Start` inherits the process environment, the
cleanest path is writing a `.env` file into the nodejs-project dir (cobalt loads
`dotenv/config`), so values survive across the JNI boundary deterministically.
`cookies.json` lives inside the project dir so `COOKIE_PATH` is stable.

### Flow D — Logout
1. User taps logout for a service in `AccountsSheet`.
2. `CookieStore.removeService(service)` drops that key from `cookies.json`.
3. `CookieManager` cookies for the domain are cleared.
4. `NodeServerManager.restartServer()` reloads cobalt without those cookies.

---

## Error Handling

| Error | Owner | User sees |
|---|---|---|
| Login WebView fails to load | `LoginActivity.onReceivedError` | Cobalt-styled error screen + retry |
| Login completed but required cookies absent | `LoginActivity` | "couldn't capture login — try again" snackbar; stays on page |
| po_token capture times out (no `/player` request in 60s) | `PoTokenManager` | Silent retry x3 w/ backoff; YouTube status dot shows "token pending"; downloads still attempted (cobalt may work ≤1080p without it) |
| po_token too short (<160) | `PoTokenManager` | Discarded; retried; logged |
| `/get_pot` polled before first token | `PoTokenServer` | Returns HTTP 503; cobalt logs a warning and retries on its interval (non-fatal) |
| Cookies present but service still errors (expired) | cobalt → `CobaltApiClient` | Inline error banner; AccountsSheet flags service "re-login needed" |
| NanoHTTPD port 9001 busy | `PoTokenServer` | Falls back to next free port; env var updated accordingly before node start |
| Server restart races a download | `NodeServerManager` | Restart deferred until active downloads drain (queue check) |

---

## Permissions
No new Android permissions. INTERNET + existing set cover WebView and the
loopback NanoHTTPD server (localhost only, never bound to a public interface).

---

## Testing Plan

### Unit (JVM)
- `CookieStore` format: merge/replace/remove services → exact cobalt JSON shape.
- Required-cookie detection: per-service positive/negative cookie sets.
- po_token JSON extraction: given a captured `/player` body fixture, extract the
  two paths; reject bodies missing them; reject po_token <160 chars.
- `PoTokenServer` response shape: `{potoken, visitor_data, updated}`; 503 before ready.

### Instrumented (emulator)
- `LoginActivity` loads a login URL and `CookieManager` round-trips a test cookie.
- `PoTokenServer` serves a seeded token over loopback; a local POST to
  `/get_pot` returns the expected JSON.

### Manual smoke (S22 Ultra, device-verified)
1. Accounts → YouTube → log in → cookies.json gains `youtube` key → server restarts.
2. po_token: within ~30s of YouTube login, `curl 127.0.0.1:9001/get_pot` (via adb)
   returns a ≥160-char potoken + visitor_data.
3. Download a 4K/AV1 YouTube video → succeeds (proves po_token path end-to-end).
4. Download a normal YouTube video while logged in → succeeds.
5. Instagram login → a private/login-gated post downloads.
6. Logout YouTube → cookies.json loses `youtube` key → 4K download now errors
   (proves cookies were the enabler).
7. cobalt startup banner logs `poToken & visitor_data loaded successfully`.

---

## Tech Stack additions
- `org.nanohttpd:nanohttpd:2.3.1`
- `assets/js/potoken_capture.js` (fetch/XHR wrapper + player trigger)
- No change to nodejs-mobile or the cobalt bundle beyond the `.env` file + cookies.json placement.

---

## Files

```
app/src/main/
├── assets/js/potoken_capture.js                 ← NEW
├── java/com/andrometa/pullout/
│   ├── auth/
│   │   ├── CookieStore.kt                        ← NEW
│   │   ├── AccountStatus.kt                       ← NEW
│   │   ├── LoginActivity.kt                       ← NEW
│   │   ├── PoTokenWebView.kt                      ← NEW
│   │   ├── PoTokenBridge.kt                       ← NEW
│   │   ├── PoTokenServer.kt                        ← NEW
│   │   └── PoTokenManager.kt                       ← NEW
│   ├── ui/AccountsSheet.kt                         ← NEW
│   ├── server/CobaltServerService.kt              ← MODIFY (env injection)
│   └── server/NodeServerManager.kt                ← MODIFY (restartServer + .env write)
└── res/
    ├── layout/{activity_login,sheet_accounts,item_account}.xml  ← NEW
    └── values/strings.xml                          ← MODIFY (account strings)
scripts/setup-cobalt.ps1                            ← MODIFY (ensure cookies.json placeholder + .env note)
```
