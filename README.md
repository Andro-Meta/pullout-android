# PULLOUT

Self-contained Android media downloader. No setup, no external server.

Bundles [cobalt](https://github.com/imputnet/cobalt)'s extraction engine on-device via nodejs-mobile. Supports YouTube, TikTok, Instagram, Twitter/X, Reddit, SoundCloud, Vimeo, Twitch, and 15+ more platforms.

## Install

Download `app-debug.apk` from [Releases](../../releases), enable "Install unknown apps" on your device, open the APK and install.

## Usage

- Share any media link from another app → select PULLOUT
- Copy a link and open the app → tap the clipboard prompt
- Long-press the home icon → Paste & Download shortcut

## How it works

A patched build of cobalt's Node.js API server runs on `localhost:9000` inside the app via nodejs-mobile. The native Android UI calls the API, downloads streams via OkHttp, and muxes video+audio via a bundled FFmpeg binary. All on-device, no internet dependency beyond the actual media fetch.

## Credits

Extraction engine: [cobalt](https://github.com/imputnet/cobalt) by imputnet and contributors.
