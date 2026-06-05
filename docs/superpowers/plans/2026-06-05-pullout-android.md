# PULLOUT Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fully self-contained Android media downloader where a patched cobalt API server runs on-device via nodejs-mobile, the native UI calls localhost:9000, and ffmpeg-kit-android handles video+audio muxing.

**Architecture:** CobaltServerService starts nodejs-mobile running a patched cobalt API on localhost:9000. MainActivity calls CobaltApiClient (OkHttp POST) with localProcessing:"forced". tunnel/redirect responses go through OkHttp→MediaStore. local-processing responses go through LocalMuxingManager (ffmpeg-kit downloads both streams → muxes → MediaStore). Room+WorkManager handle history/retry.

**Tech Stack:** Kotlin 1.9.23 · AGP 8.5.2 · Gradle 8.7 · KSP 1.9.23-1.0.20 · Room 2.6.1 · WorkManager 2.9.0 · OkHttp 4.12.0 · Coroutines 1.8.1 · Material3 1.12.0 · androidx.browser · nodejs-mobile-android AAR · ffmpeg-kit-android-min 6.0 · Space Mono (Google Fonts) · minSdk 26 · targetSdk 35

---

## Conventions for all tasks

- All paths are relative to `D:\Projects\PULLOUT\` unless absolute.
- All commands run from `D:\Projects\PULLOUT\` in PowerShell unless noted.
- Package is `com.andrometa.pullout`. Java/Kotlin root: `app\src\main\java\com\andrometa\pullout\`.
- After each task: build, then commit. Never use `--no-verify`.
- The git repo is initialized in Task 1; every later task ends with a commit.

---

### Task 1: Cobalt setup script + Gradle bootstrap

**Files:** Create `scripts\setup-cobalt.ps1`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app\build.gradle.kts`, `gradle\wrapper\gradle-wrapper.properties`, `.gitignore`, `app\libs\nodejs-mobile-android.aar` (downloaded)

- [ ] Step 1: Initialize git and create directory structure.
```powershell
cd D:\Projects\PULLOUT
git init
New-Item -ItemType Directory -Force -Path scripts, app\libs, app\src\main\assets, "app\src\main\java\com\andrometa\pullout", "gradle\wrapper" | Out-Null
```
Expected: directories created, `Initialized empty Git repository`.

- [ ] Step 2: Write `.gitignore`.
```gitignore
.gradle/
build/
/local.properties
.idea/
*.iml
app/src/main/assets/nodejs-project/
app/libs/nodejs-mobile-android.aar
cobalt-src/
.cxx/
*.apk
*.keystore
```

- [ ] Step 3: Write `scripts\setup-cobalt.ps1` (clones cobalt, patches it, builds a self-contained nodejs-project into assets).
```powershell
# scripts/setup-cobalt.ps1
# Clones imputnet/cobalt, patches out native deps (isolated-vm, ffmpeg-static),
# builds a self-contained API bundle and copies it into the app assets.
$ErrorActionPreference = "Stop"
$Root      = Split-Path -Parent $PSScriptRoot
$SrcDir    = Join-Path $Root "cobalt-src"
$AssetsDir = Join-Path $Root "app\src\main\assets\nodejs-project"

Write-Host "==> PULLOUT cobalt setup"

# 1. Ensure pnpm exists
if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) {
    Write-Host "Installing pnpm via corepack..."
    corepack enable
    corepack prepare pnpm@latest --activate
}

# 2. Clone cobalt (shallow)
if (Test-Path $SrcDir) { Remove-Item -Recurse -Force $SrcDir }
git clone --depth=1 https://github.com/imputnet/cobalt.git $SrcDir
$GitHash = (git -C $SrcDir rev-parse --short HEAD).Trim()
Write-Host "cobalt @ $GitHash"

# 3. Detect the api package name from api/package.json
$ApiPkgJsonPath = Join-Path $SrcDir "api\package.json"
$ApiPkgJson     = Get-Content $ApiPkgJsonPath -Raw | ConvertFrom-Json
$ApiPkgName     = $ApiPkgJson.name
Write-Host "api package name: $ApiPkgName"

# 4. Patch youtube.js: remove isolated-vm import and replace the vm eval block
$YtPath = Join-Path $SrcDir "api\src\processing\services\youtube.js"
if (Test-Path $YtPath) {
    $yt = Get-Content $YtPath -Raw
    # remove any import line that pulls isolated-vm
    $yt = ($yt -split "`r?`n" | Where-Object { $_ -notmatch "isolated-vm" }) -join "`n"
    # Replace any Platform.shim.eval(...) or ivm-based eval with a plain Function eval.
    # cobalt evaluates a player JS string; we substitute a sandbox-free evaluator.
    $yt = $yt -replace "Platform\.shim\.eval\(", "globalThis.__pulloutEval("
    # Prepend a __pulloutEval shim that uses new Function().
    $shim = "globalThis.__pulloutEval = (code) => { return (new Function('return (' + code + ')'))(); };`n"
    $yt = $shim + $yt
    Set-Content -Path $YtPath -Value $yt -NoNewline
    Write-Host "patched youtube.js"
} else {
    Write-Host "WARN: youtube.js not found at expected path; skipping youtube patch"
}

# 5. Strip native-only deps from api/package.json (isolated-vm, ffmpeg-static)
$ApiPkgRaw = Get-Content $ApiPkgJsonPath -Raw | ConvertFrom-Json
foreach ($dep in @("isolated-vm","ffmpeg-static")) {
    if ($ApiPkgRaw.dependencies.PSObject.Properties.Name -contains $dep) {
        $ApiPkgRaw.dependencies.PSObject.Properties.Remove($dep)
        Write-Host "removed dependency: $dep"
    }
}
($ApiPkgRaw | ConvertTo-Json -Depth 50) | Set-Content -Path $ApiPkgJsonPath -NoNewline

# 6. Write main.js entry point into the api package directory
$ApiDir  = Join-Path $SrcDir "api"
$MainJs  = Join-Path $ApiDir "main.js"
@'
// PULLOUT nodejs-mobile entry point.
// Sets required env vars, then boots the cobalt API.
process.env.API_URL = process.env.API_URL || "http://localhost:9000/";
process.env.API_PORT = process.env.API_PORT || "9000";
process.env.API_LISTEN_ADDRESS = process.env.API_LISTEN_ADDRESS || "127.0.0.1";
process.env.DISABLED_SERVICES = process.env.DISABLED_SERVICES || "";
// cobalt local-processing is requested per-request by the client (localProcessing:"forced"),
// so no transcoding deps are needed on-device beyond ffmpeg-kit on the Kotlin side.
import("./src/cobalt.js").catch((e) => {
    console.error("PULLOUT: failed to boot cobalt:", e);
    process.exit(1);
});
'@ | Set-Content -Path $MainJs -NoNewline
Write-Host "wrote main.js"

# 7. Install workspace deps at repo root
Push-Location $SrcDir
pnpm install --no-frozen-lockfile
Pop-Location

# 8. Record version
$VersionTxt = Join-Path $AssetsDir "..\pullout-version.txt"

# 9. pnpm deploy a self-contained copy of the api package
$DeployDir = Join-Path $Root "cobalt-deploy"
if (Test-Path $DeployDir) { Remove-Item -Recurse -Force $DeployDir }
Push-Location $SrcDir
pnpm --filter "$ApiPkgName" deploy --prod --legacy $DeployDir
Pop-Location

# 10. Copy deploy output into assets/nodejs-project
if (Test-Path $AssetsDir) { Remove-Item -Recurse -Force $AssetsDir }
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null
Copy-Item -Recurse -Force (Join-Path $DeployDir "*") $AssetsDir

# 11. Ensure main.js is present at the assets root (deploy keeps package files)
if (-not (Test-Path (Join-Path $AssetsDir "main.js"))) {
    Copy-Item -Force $MainJs (Join-Path $AssetsDir "main.js")
}

# 12. Write version file (used by NodeServerManager to decide re-extraction)
Set-Content -Path (Join-Path $AssetsDir "pullout-version.txt") -Value $GitHash -NoNewline

Write-Host "==> nodejs-project ready at $AssetsDir (cobalt $GitHash)"
```

- [ ] Step 4: Download the nodejs-mobile-android AAR. Find the latest release asset named like `nodejs-mobile-android-*-release.aar`.
```powershell
cd D:\Projects\PULLOUT
$rel = gh release view --repo nodejs-mobile/nodejs-mobile-android --json assets,tagName 2>$null | ConvertFrom-Json
if (-not $rel) {
  # fallback: query the API
  $rel = gh api repos/nodejs-mobile/nodejs-mobile-android/releases/latest | ConvertFrom-Json
}
$asset = $rel.assets | Where-Object { $_.name -match "release\.aar$" } | Select-Object -First 1
Write-Host "Downloading $($asset.name) from $($rel.tagName)"
gh release download $rel.tagName --repo nodejs-mobile/nodejs-mobile-android --pattern "*release.aar" --output app\libs\nodejs-mobile-android.aar --clobber
```
Expected: `app\libs\nodejs-mobile-android.aar` exists, size > 20 MB (it bundles native libnode for arm64/armv7/x86/x86_64).

- [ ] Step 5: Inspect the AAR to confirm the startup class name (used in Task 6).
```powershell
cd D:\Projects\PULLOUT
jar tf app\libs\nodejs-mobile-android.aar | Select-String -Pattern "nodejs|NodeJS" -CaseSensitive:$false
```
Expected: a path like `classes.jar` and inside it a `.../nodejsmobile/...` package. Note the actual class (commonly `com.staltz.nodejsmobile.NodeJsMobile` or `org.nodejsmobile.NodeJSMobile`). Record the FQ class name in a comment for Task 6. If the class lives only in `classes.jar`, extract and list it:
```powershell
$tmp = New-Item -ItemType Directory -Force -Path "$env:TEMP\pullout-aar"
Expand-Archive -Force app\libs\nodejs-mobile-android.aar -DestinationPath $tmp
jar tf (Join-Path $tmp "classes.jar") | Select-String -Pattern "nodejs" -CaseSensitive:$false
```
Expected output contains exactly one class with a `startNodeWithArguments` method (verify with `javap`):
```powershell
javap -classpath (Join-Path $tmp "classes.jar") <fully.qualified.ClassName>
```
Record the FQ name. The rest of this plan refers to it as `NODE_CLASS`. (If it is the staltz fork it is `com.staltz.nodejsmobile.NodeJsMobile` with `public static int startNodeWithArguments(String[] args)` and `public static int startNodeWithArgumentsAndOptions(String[] args, String options)`.)

- [ ] Step 6: Write `gradle\wrapper\gradle-wrapper.properties`.
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] Step 7: Generate the wrapper jar + scripts using a locally installed Gradle (or copy from any AGP project). If Gradle is on PATH:
```powershell
cd D:\Projects\PULLOUT
gradle wrapper --gradle-version 8.7 --distribution-type bin
```
Expected: creates `gradlew`, `gradlew.bat`, `gradle\wrapper\gradle-wrapper.jar`. If Gradle is not installed, install via `choco install gradle --version=8.7` first.

- [ ] Step 8: Write `settings.gradle.kts`.
```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PULLOUT"
include(":app")
```

- [ ] Step 9: Write root `build.gradle.kts`.
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("com.google.devtools.ksp") version "1.9.23-1.0.20" apply false
}
```

- [ ] Step 10: Write `gradle.properties`.
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] Step 11: Write `app\build.gradle.kts`.
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.andrometa.pullout"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andrometa.pullout"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // nodejs-mobile ships native libs for these ABIs; keep them all for broad device support.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    // nodejs-mobile native .so files must not be compressed/stripped incorrectly.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // assets can be large (node_modules); do not compress them so they can be extracted at runtime.
    androidResources {
        noCompress += listOf("js", "node", "json")
    }
}

dependencies {
    // nodejs-mobile runtime
    implementation(files("libs/nodejs-mobile-android.aar"))

    // ffmpeg muxing
    implementation("com.arthenica:ffmpeg-kit-android-min:6.0")

    // AndroidX core / UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.0")
    implementation("androidx.browser:browser:1.8.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Coil (image thumbnails for picker)
    implementation("io.coil-kt:coil:2.4.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
```

- [ ] Step 12: Write `app\proguard-rules.pro` (release-only; keeps any future JNI bridge members and Room).
```proguard
# Keep nodejs-mobile native bridge class and JNI entrypoints.
-keep class **.nodejsmobile.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}
# Keep any @JavascriptInterface-annotated members (future-proofing JNI bridges).
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
# ffmpeg-kit
-keep class com.arthenica.ffmpegkit.** { *; }
```

- [ ] Step 13: Run cobalt setup, then build.
```powershell
cd D:\Projects\PULLOUT
powershell -ExecutionPolicy Bypass -File scripts\setup-cobalt.ps1
.\gradlew.bat assembleDebug
```
Expected: setup prints `==> nodejs-project ready ...`; `app\src\main\assets\nodejs-project\main.js` exists; gradle prints `BUILD SUCCESSFUL`. (At this point there is no app code yet, only resources/manifest come in Task 2 — if AGP complains about a missing manifest, proceed to Task 2 and run the build there; otherwise an empty default manifest is auto-generated and the build succeeds.)

- [ ] Step 14: Commit.
```bash
git add -A && git commit -m "feat: cobalt setup script + gradle bootstrap + nodejs-mobile AAR"
```

---

### Task 2: Manifest + permissions + all resources

**Files:** Create `app\src\main\AndroidManifest.xml`, `app\src\main\res\xml\network_security_config.xml`, `app\src\main\res\xml\shortcuts.xml`, `app\src\main\res\values\{colors,themes,strings,dimens}.xml`, `app\src\main\res\font\{space_mono_regular.ttf,space_mono_bold.ttf}`, drawables, mipmap adaptive icons.

- [ ] Step 1: Write `app\src\main\res\values\colors.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="background">#060608</color>
    <color name="surface">#0E0E16</color>
    <color name="surface_elevated">#161624</color>
    <color name="neon_cyan">#00E5FF</color>
    <color name="neon_cyan_dim">#0099BB</color>
    <color name="neon_magenta">#C855F0</color>
    <color name="neon_green">#39FF14</color>
    <color name="neon_red">#FF2244</color>
    <color name="neon_amber">#FFB300</color>
    <color name="text_primary">#D8E4FF</color>
    <color name="text_secondary">#3A4870</color>
    <color name="text_dim">#1A2040</color>
    <color name="border">#0A1030</color>
    <color name="cyan_30">#4D00E5FF</color>
    <color name="cyan_66">#A800E5FF</color>
</resources>
```

- [ ] Step 2: Write `app\src\main\res\values\dimens.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <dimen name="border_radius">3dp</dimen>
    <dimen name="padding_default">16dp</dimen>
    <dimen name="fab_size">48dp</dimen>
    <dimen name="progress_bar_height">2dp</dimen>
    <dimen name="badge_size">18dp</dimen>
    <dimen name="wordmark_size">40sp</dimen>
</resources>
```

- [ ] Step 3: Write `app\src\main\res\values\strings.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">PULLOUT</string>
    <string name="wordmark">PULLOUT</string>
    <string name="url_hint">paste or type a url</string>
    <string name="pull">[ PULL ]</string>
    <string name="settings_glyph">⋯</string>

    <string name="status_init">▓ INITIALIZING…</string>
    <string name="status_ready">▓ READY</string>
    <string name="status_offline">▓ OFFLINE</string>
    <string name="status_working">▓ PULLING…</string>

    <string name="server_running">PULLOUT — server running</string>
    <string name="server_channel">PULLOUT downloads</string>
    <string name="notif_downloading">Downloading…</string>
    <string name="notif_complete">Saved</string>
    <string name="notif_failed">Failed</string>
    <string name="notif_storage_full">Storage full</string>
    <string name="notif_retry_ready">Tap to retry</string>

    <string name="queue_active">ACTIVE</string>
    <string name="queue_history">HISTORY</string>
    <string name="queue_empty">no downloads yet</string>

    <string name="action_open">OPEN</string>
    <string name="action_share">SHARE</string>
    <string name="action_retry">RETRY</string>
    <string name="action_cancel">CANCEL</string>
    <string name="action_pull_all">[ PULL ALL ]</string>
    <string name="action_pull_selected">[ PULL SELECTED ]</string>

    <string name="settings_title">SETTINGS</string>
    <string name="settings_cobalt_url">cobalt instance url</string>
    <string name="settings_audio_only">audio only</string>
    <string name="settings_quality">quality</string>
    <string name="settings_battery">disable battery optimization</string>
    <string name="settings_clear_history">CLEAR HISTORY</string>
    <string name="settings_docs">cobalt api docs</string>

    <string name="picker_title">select items to pull</string>

    <string name="clip_prompt">pull from clipboard?</string>
    <string name="clip_action">PULL</string>

    <string name="err_invalid_url">not a supported url</string>
    <string name="err_generic">something went wrong</string>
    <string name="err_offline">server offline — retry</string>

    <!-- cobalt error code translations -->
    <string name="err_api_unreachable">cannot reach the server</string>
    <string name="err_link_invalid">that link is not valid</string>
    <string name="err_link_unsupported">this service is not supported</string>
    <string name="err_content_unavailable">content unavailable</string>
    <string name="err_content_too_long">that video is too long</string>
    <string name="err_rate_limit">rate limited — try again later</string>

    <string name="shortcut_paste_short">Paste</string>
    <string name="shortcut_paste_long">Pull from clipboard</string>
    <string name="shortcut_queue_short">Queue</string>
    <string name="shortcut_queue_long">Open download queue</string>
</resources>
```

- [ ] Step 4: Download Space Mono fonts into `app\src\main\res\font\` (lowercase, no hyphens, valid resource names).
```powershell
cd D:\Projects\PULLOUT
New-Item -ItemType Directory -Force -Path app\src\main\res\font | Out-Null
# Space Mono from Google Fonts github mirror (static TTFs).
Invoke-WebRequest -Uri "https://github.com/google/fonts/raw/main/ofl/spacemono/SpaceMono-Regular.ttf" -OutFile "app\src\main\res\font\space_mono_regular.ttf"
Invoke-WebRequest -Uri "https://github.com/google/fonts/raw/main/ofl/spacemono/SpaceMono-Bold.ttf" -OutFile "app\src\main\res\font\space_mono_bold.ttf"
Get-Item app\src\main\res\font\*.ttf | Select-Object Name, Length
```
Expected: two TTF files, each > 30 KB.

- [ ] Step 5: Write `app\src\main\res\values\themes.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Pullout" parent="Theme.Material3.Dark.NoActionBar">
        <item name="android:statusBarColor">@color/background</item>
        <item name="android:navigationBarColor">@color/background</item>
        <item name="android:windowBackground">@color/background</item>
        <item name="colorPrimary">@color/neon_cyan</item>
        <item name="colorOnPrimary">@color/background</item>
        <item name="colorSurface">@color/surface</item>
        <item name="colorOnSurface">@color/text_primary</item>
        <item name="android:fontFamily">@font/space_mono_regular</item>
        <item name="bottomSheetDialogTheme">@style/Theme.Pullout.BottomSheet</item>
    </style>

    <style name="Theme.Pullout.BottomSheet" parent="ThemeOverlay.Material3.BottomSheetDialog">
        <item name="bottomSheetStyle">@style/Pullout.BottomSheet.Modal</item>
        <item name="android:fontFamily">@font/space_mono_regular</item>
    </style>

    <style name="Pullout.BottomSheet.Modal" parent="Widget.Material3.BottomSheet.Modal">
        <item name="backgroundTint">@color/surface</item>
        <item name="shapeAppearance">@style/Pullout.Sheet.Shape</item>
    </style>

    <style name="Pullout.Sheet.Shape" parent="">
        <item name="cornerFamily">cut</item>
        <item name="cornerSizeTopLeft">3dp</item>
        <item name="cornerSizeTopRight">3dp</item>
        <item name="cornerSizeBottomLeft">0dp</item>
        <item name="cornerSizeBottomRight">0dp</item>
    </style>

    <style name="TextAppearance.Pullout.Mono" parent="TextAppearance.Material3.BodyMedium">
        <item name="android:fontFamily">@font/space_mono_regular</item>
        <item name="android:textColor">@color/text_primary</item>
    </style>

    <style name="TextAppearance.Pullout.MonoBold" parent="TextAppearance.Material3.TitleMedium">
        <item name="android:fontFamily">@font/space_mono_bold</item>
        <item name="android:textColor">@color/text_primary</item>
    </style>

    <style name="Widget.Pullout.OutlinedButton" parent="Widget.Material3.Button.OutlinedButton">
        <item name="android:fontFamily">@font/space_mono_bold</item>
        <item name="android:textColor">@color/neon_cyan</item>
        <item name="strokeColor">@color/neon_cyan_dim</item>
        <item name="cornerRadius">3dp</item>
        <item name="android:textAllCaps">true</item>
    </style>
</resources>
```

- [ ] Step 6: Write `app\src\main\res\xml\network_security_config.xml` (cleartext only to localhost).
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

- [ ] Step 7: Write `app\src\main\res\xml\shortcuts.xml` (placeholder, finalized in Task 12).
```xml
<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] Step 8: Write drawables.

`app\src\main\res\drawable\ic_queue.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="@color/neon_cyan">
    <path android:fillColor="@android:color/white"
        android:pathData="M3,5h18v2H3zM3,11h18v2H3zM3,17h12v2H3z"/>
</vector>
```

`app\src\main\res\drawable\bg_fab.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/surface"/>
    <stroke android:width="1dp" android:color="@color/neon_cyan"/>
</shape>
```

`app\src\main\res\drawable\bg_badge.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/neon_cyan"/>
</shape>
```

`app\src\main\res\drawable\bg_input_normal.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface"/>
    <stroke android:width="1dp" android:color="@color/border"/>
    <corners android:radius="@dimen/border_radius"/>
</shape>
```

`app\src\main\res\drawable\bg_input_focused.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface"/>
    <stroke android:width="1dp" android:color="@color/cyan_66"/>
    <corners android:radius="@dimen/border_radius"/>
</shape>
```

`app\src\main\res\drawable\bg_button_pull.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@android:color/transparent"/>
    <stroke android:width="1dp" android:color="@color/neon_cyan"/>
    <corners android:radius="@dimen/border_radius"/>
</shape>
```

- [ ] Step 9: Write adaptive launcher icon. `app\src\main\res\mipmap-anydpi-v26\ic_launcher.xml` and `ic_launcher_round.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
```
`app\src\main\res\drawable\ic_launcher_background.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/background"/>
</shape>
```
`app\src\main\res\drawable\ic_launcher_foreground.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#00E5FF"
        android:pathData="M30,40h12v28h-6V46h-6zM50,40h12a6,6 0 0 1 6,6v8a6,6 0 0 1 -6,6h-6v8h-6zM56,46v8h6v-8z"/>
</vector>
```

- [ ] Step 10: Write `app\src\main\AndroidManifest.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>

    <application
        android:name=".PulloutApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@style/Theme.Pullout"
        android:extractNativeLibs="true"
        tools:targetApi="35">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:theme="@style/Theme.Pullout">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <data android:mimeType="text/plain"/>
            </intent-filter>
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts"/>
        </activity>

        <service
            android:name=".server.CobaltServerService"
            android:exported="false"
            android:foregroundServiceType="dataSync"/>

        <service
            android:name=".download.DownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync"/>
    </application>
</manifest>
```

- [ ] Step 11: Build to verify resources compile.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:processDebugResources
```
Expected: `BUILD SUCCESSFUL`. (Full `assembleDebug` will fail until Task 12 provides `PulloutApplication`/`MainActivity`/services; resource processing succeeds now.)

- [ ] Step 12: Commit.
```bash
git add -A && git commit -m "feat: manifest, permissions, fonts, colors, drawables, themes"
```

---

### Task 3: DownloadRecord + Room database + unit tests

**Files:** Create `app\src\main\java\com\andrometa\pullout\download\{DownloadRecord.kt,DownloadDao.kt,DownloadDatabase.kt}`, `app\src\test\java\com\andrometa\pullout\DownloadStatusTest.kt`

- [ ] Step 1: Write `DownloadRecord.kt`.
```kotlin
package com.andrometa.pullout.download

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETE,
    FAILED,
    FAILED_NETWORK
}

class StatusConverters {
    @TypeConverter
    fun fromStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.QUEUED)
}

@Entity(tableName = "downloads")
@TypeConverters(StatusConverters::class)
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalUrl: String,
    val cobaltUrl: String,
    val filename: String,
    val mimeType: String,
    val cookies: String = "",
    val userAgent: String = "",
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val timestamp: Long = System.currentTimeMillis(),
    val isBlobDownload: Boolean = false,
    val tempFilePath: String = "",
    val retryCount: Int = 0,
    val mediaStoreUriString: String = "",
    val isMuxed: Boolean = false,
    val muxPhase: String = ""
)
```

- [ ] Step 2: Write `DownloadDao.kt`.
```kotlin
package com.andrometa.pullout.download

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(record: DownloadRecord): Long

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus)

    @Query("UPDATE downloads SET bytesDownloaded = :bytes, totalBytes = :total WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, total: Long)

    @Query("UPDATE downloads SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("UPDATE downloads SET mediaStoreUriString = :uri WHERE id = :id")
    suspend fun updateMediaStoreUri(id: Long, uri: String)

    @Query("UPDATE downloads SET muxPhase = :phase WHERE id = :id")
    suspend fun updateMuxPhase(id: Long, phase: String)

    @Query("UPDATE downloads SET status = :failed WHERE status = :downloading")
    suspend fun resetStuckDownloads(
        downloading: DownloadStatus = DownloadStatus.DOWNLOADING,
        failed: DownloadStatus = DownloadStatus.FAILED
    )

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun getAllLive(): LiveData<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','DOWNLOADING') ORDER BY timestamp DESC")
    fun getActiveLive(): LiveData<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadRecord?

    @Query("DELETE FROM downloads WHERE status IN ('COMPLETE','FAILED','FAILED_NETWORK')")
    suspend fun clearHistory()
}
```

- [ ] Step 3: Write `DownloadDatabase.kt`.
```kotlin
package com.andrometa.pullout.download

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [DownloadRecord::class], version = 1, exportSchema = false)
@TypeConverters(StatusConverters::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        fun get(context: Context): DownloadDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DownloadDatabase::class.java,
                    "pullout-downloads.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
```

- [ ] Step 4: Write `app\src\test\java\com\andrometa\pullout\DownloadStatusTest.kt`.
```kotlin
package com.andrometa.pullout

import com.andrometa.pullout.download.DownloadRecord
import com.andrometa.pullout.download.DownloadStatus
import com.andrometa.pullout.download.StatusConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatusTest {
    private val converters = StatusConverters()

    @Test
    fun enumRoundTrip() {
        for (s in DownloadStatus.values()) {
            assertEquals(s, converters.toStatus(converters.fromStatus(s)))
        }
    }

    @Test
    fun unknownStringDefaultsToQueued() {
        assertEquals(DownloadStatus.QUEUED, converters.toStatus("NOT_A_STATUS"))
    }

    @Test
    fun defaultRecordStatusIsQueued() {
        val r = DownloadRecord(
            originalUrl = "u", cobaltUrl = "c", filename = "f", mimeType = "video/mp4"
        )
        assertEquals(DownloadStatus.QUEUED, r.status)
    }

    @Test
    fun isMuxedDefaultsFalseAndMuxPhaseEmpty() {
        val r = DownloadRecord(
            originalUrl = "u", cobaltUrl = "c", filename = "f", mimeType = "video/mp4"
        )
        assertFalse(r.isMuxed)
        assertTrue(r.muxPhase.isEmpty())
    }

    @Test
    fun muxedRecordRetainsPhase() {
        val r = DownloadRecord(
            originalUrl = "u", cobaltUrl = "c", filename = "f", mimeType = "video/mp4",
            isMuxed = true, muxPhase = "MERGING"
        )
        assertTrue(r.isMuxed)
        assertEquals("MERGING", r.muxPhase)
    }
}
```

- [ ] Step 5: Run tests.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:testDebugUnitTest --tests "com.andrometa.pullout.DownloadStatusTest"
```
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] Step 6: Commit.
```bash
git add -A && git commit -m "feat: Room DownloadRecord/Dao/Database + status tests"
```

---

### Task 4: Utilities

**Files:** Create `app\src\main\java\com\andrometa\pullout\util\{UrlMatcher.kt,ClipboardHelper.kt,SettingsRepository.kt,NotificationHelper.kt}`, `app\src\test\java\com\andrometa\pullout\UrlMatcherTest.kt`

- [ ] Step 1: Write `util\UrlMatcher.kt`.
```kotlin
package com.andrometa.pullout.util

import java.net.URL

object UrlMatcher {
    val SUPPORTED_HOSTS: Set<String> = setOf(
        "youtube.com", "youtu.be", "music.youtube.com",
        "tiktok.com", "vm.tiktok.com",
        "twitter.com", "x.com",
        "instagram.com",
        "reddit.com",
        "soundcloud.com",
        "vimeo.com",
        "twitch.tv", "clips.twitch.tv",
        "dailymotion.com", "dai.ly",
        "bilibili.com",
        "pinterest.com", "pin.it",
        "tumblr.com"
    )

    val URL_REGEX = Regex("""https?://\S+""")

    fun isSupportedUrl(raw: String): Boolean {
        val cleaned = raw.trim()
        return try {
            val host = URL(cleaned).host.lowercase().removePrefix("www.")
            SUPPORTED_HOSTS.any { host == it || host.endsWith(".$it") }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns a supported URL extracted from arbitrary share text, or null.
     * Fast path: the whole trimmed string is already a bare supported URL.
     * Slow path: regex-scan, trim trailing punctuation, test each candidate.
     */
    fun extractUrl(text: String?): String? {
        if (text == null) return null
        val trimmed = text.trim()
        if (isSupportedUrl(trimmed)) return trimmed
        for (match in URL_REGEX.findAll(trimmed)) {
            val candidate = match.value.trimEnd('.', ',', '\'', '!', '?', ')', ']', '}', '"', ';', ':')
            if (isSupportedUrl(candidate)) return candidate
        }
        return null
    }
}
```

- [ ] Step 2: Write `app\src\test\java\com\andrometa\pullout\UrlMatcherTest.kt` (24 tests).
```kotlin
package com.andrometa.pullout

import com.andrometa.pullout.util.UrlMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlMatcherTest {
    @Test fun youtubeLong() = assertTrue(UrlMatcher.isSupportedUrl("https://www.youtube.com/watch?v=abc"))
    @Test fun youtubeShort() = assertTrue(UrlMatcher.isSupportedUrl("https://youtu.be/abc"))
    @Test fun youtubeMusic() = assertTrue(UrlMatcher.isSupportedUrl("https://music.youtube.com/watch?v=abc"))
    @Test fun tiktok() = assertTrue(UrlMatcher.isSupportedUrl("https://www.tiktok.com/@u/video/1"))
    @Test fun tiktokVm() = assertTrue(UrlMatcher.isSupportedUrl("https://vm.tiktok.com/ZMabc/"))
    @Test fun twitter() = assertTrue(UrlMatcher.isSupportedUrl("https://twitter.com/u/status/1"))
    @Test fun xDotCom() = assertTrue(UrlMatcher.isSupportedUrl("https://x.com/u/status/1"))
    @Test fun instagram() = assertTrue(UrlMatcher.isSupportedUrl("https://www.instagram.com/reel/abc/"))
    @Test fun reddit() = assertTrue(UrlMatcher.isSupportedUrl("https://www.reddit.com/r/x/comments/1/t/"))
    @Test fun soundcloud() = assertTrue(UrlMatcher.isSupportedUrl("https://soundcloud.com/a/b"))
    @Test fun vimeo() = assertTrue(UrlMatcher.isSupportedUrl("https://vimeo.com/123456"))
    @Test fun twitch() = assertTrue(UrlMatcher.isSupportedUrl("https://www.twitch.tv/videos/1"))
    @Test fun twitchClips() = assertTrue(UrlMatcher.isSupportedUrl("https://clips.twitch.tv/abc"))
    @Test fun dailymotion() = assertTrue(UrlMatcher.isSupportedUrl("https://www.dailymotion.com/video/x1"))
    @Test fun dailyShort() = assertTrue(UrlMatcher.isSupportedUrl("https://dai.ly/x1"))
    @Test fun bilibili() = assertTrue(UrlMatcher.isSupportedUrl("https://www.bilibili.com/video/BV1"))
    @Test fun pinterest() = assertTrue(UrlMatcher.isSupportedUrl("https://www.pinterest.com/pin/1/"))
    @Test fun pinIt() = assertTrue(UrlMatcher.isSupportedUrl("https://pin.it/abc"))
    @Test fun tumblr() = assertTrue(UrlMatcher.isSupportedUrl("https://www.tumblr.com/blog/123"))

    @Test fun unsupportedHost() = assertFalse(UrlMatcher.isSupportedUrl("https://example.com/x"))
    @Test fun notAUrl() = assertFalse(UrlMatcher.isSupportedUrl("hello world"))

    @Test fun embeddedInShareText() {
        val t = "check this out https://youtu.be/abc it's great"
        assertEquals("https://youtu.be/abc", UrlMatcher.extractUrl(t))
    }

    @Test fun trailingPunctuation() {
        val t = "see (https://vimeo.com/123)."
        assertEquals("https://vimeo.com/123", UrlMatcher.extractUrl(t))
    }

    @Test fun extractReturnsNullForNoUrl() {
        assertNull(UrlMatcher.extractUrl("just some words"))
    }
}
```

- [ ] Step 3: Write `util\ClipboardHelper.kt`.
```kotlin
package com.andrometa.pullout.util

import android.content.ClipboardManager
import android.content.Context

object ClipboardHelper {
    fun getSupportedUrl(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
        return UrlMatcher.extractUrl(text)
    }
}
```

- [ ] Step 4: Write `util\SettingsRepository.kt`.
```kotlin
package com.andrometa.pullout.util

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pullout_prefs", Context.MODE_PRIVATE)

    var cobaltInstanceUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(v) = prefs.edit().putString(KEY_URL, v).apply()

    var audioOnlyMode: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, false)
        set(v) = prefs.edit().putBoolean(KEY_AUDIO, v).apply()

    var clipboardTriggerEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIP, true)
        set(v) = prefs.edit().putBoolean(KEY_CLIP, v).apply()

    var defaultQuality: String
        get() = prefs.getString(KEY_QUALITY, "1080") ?: "1080"
        set(v) = prefs.edit().putString(KEY_QUALITY, v).apply()

    var firstLaunchDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST, false)
        set(v) = prefs.edit().putBoolean(KEY_FIRST, v).apply()

    companion object {
        const val DEFAULT_URL = "http://localhost:9000"
        private const val KEY_URL = "cobalt_url"
        private const val KEY_AUDIO = "audio_only"
        private const val KEY_CLIP = "clipboard_trigger"
        private const val KEY_QUALITY = "default_quality"
        private const val KEY_FIRST = "first_launch_done"
    }
}
```

- [ ] Step 5: Write `util\NotificationHelper.kt`.
```kotlin
package com.andrometa.pullout.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.andrometa.pullout.R

class NotificationHelper(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.server_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildForegroundNotification(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_queue)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun updateProgress(recordId: Long, bytes: Long, total: Long) {
        val pct = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_downloading))
            .setSmallIcon(R.drawable.ic_queue)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
        notify(BASE_ID + recordId.toInt(), builder.build())
    }

    fun showComplete(recordId: Long, filename: String, uri: Uri, mimeType: String) {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, recordId.toInt(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_complete))
            .setContentText(filename)
            .setSmallIcon(R.drawable.ic_queue)
            .setAutoCancel(true)
            .setContentIntent(pi)
        notify(BASE_ID + recordId.toInt(), builder.build())
    }

    fun showFailed(recordId: Long, filename: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_failed))
            .setContentText(filename)
            .setSmallIcon(R.drawable.ic_queue)
            .setAutoCancel(true)
        notify(BASE_ID + recordId.toInt(), builder.build())
    }

    fun showStorageFull() {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_storage_full))
            .setSmallIcon(R.drawable.ic_queue)
            .setAutoCancel(true)
        notify(BASE_ID + 99999, builder.build())
    }

    fun showRetryReady(recordId: Long, originalUrl: String, filename: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_retry_ready))
            .setContentText(filename)
            .setSmallIcon(R.drawable.ic_queue)
            .setAutoCancel(true)
        notify(BASE_ID + recordId.toInt(), builder.build())
    }

    fun cancel(recordId: Long) {
        manager.cancel(BASE_ID + recordId.toInt())
    }

    private fun notify(id: Int, notification: Notification) {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            manager.notify(id, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "pullout_downloads"
        const val FOREGROUND_ID = 1
        const val BASE_ID = 1000
    }
}
```

- [ ] Step 6: Run tests.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:testDebugUnitTest --tests "com.andrometa.pullout.UrlMatcherTest"
```
Expected: `BUILD SUCCESSFUL`, 24 tests pass.

- [ ] Step 7: Commit.
```bash
git add -A && git commit -m "feat: UrlMatcher, Clipboard, Settings, NotificationHelper + 24 url tests"
```

---

### Task 5: CobaltResponse sealed class + CobaltApiClient

**Files:** Create `app\src\main\java\com\andrometa\pullout\api\{CobaltResponse.kt,CobaltApiClient.kt}`, `app\src\test\java\com\andrometa\pullout\CobaltResponseTest.kt`

- [ ] Step 1: Write `api\CobaltResponse.kt`.
```kotlin
package com.andrometa.pullout.api

data class PickerItem(
    val type: String,
    val url: String,
    val thumb: String?
)

sealed class CobaltResponse {
    data class Tunnel(
        val url: String,
        val filename: String,
        val mimeType: String
    ) : CobaltResponse()

    data class Redirect(
        val url: String,
        val filename: String,
        val mimeType: String
    ) : CobaltResponse()

    data class LocalProcessing(
        val type: String,
        val tunnels: List<String>,
        val outputFilename: String,
        val outputMimeType: String,
        val service: String
    ) : CobaltResponse()

    data class Picker(
        val items: List<PickerItem>,
        val backgroundAudio: String?,
        val backgroundAudioFilename: String?
    ) : CobaltResponse()

    data class CobaltError(
        val code: String,
        val context: Map<String, Any?>?
    ) : CobaltResponse()
}
```

- [ ] Step 2: Write `api\CobaltApiClient.kt`.
```kotlin
package com.andrometa.pullout.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CobaltApiClient {

    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Posts a request to the cobalt API and parses the result into a CobaltResponse.
     * Always runs on a background dispatcher (call from a coroutine on Dispatchers.IO).
     */
    fun submit(
        baseUrl: String,
        url: String,
        audioOnly: Boolean,
        quality: String
    ): CobaltResponse {
        val payload = JSONObject().apply {
            put("url", url)
            put("localProcessing", "forced")
            put("downloadMode", if (audioOnly) "audio" else "auto")
            put("videoQuality", quality)
        }

        val request = Request.Builder()
            .url(normalizeBase(baseUrl))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                return@use CobaltResponse.CobaltError("api.unreachable", null)
            }
            parse(JSONObject(body))
        }
    }

    private fun normalizeBase(base: String): String =
        if (base.endsWith("/")) base else "$base/"

    private fun parse(json: JSONObject): CobaltResponse {
        return when (json.optString("status")) {
            "tunnel" -> {
                val u = json.getString("url")
                val fn = json.optString("filename", generatedName("media", "mp4"))
                CobaltResponse.Tunnel(u, fn, getMimeType("tunnel", json, fn))
            }
            "redirect" -> {
                val u = json.getString("url")
                val fn = json.optString("filename", generatedName("media", "mp4"))
                CobaltResponse.Redirect(u, fn, getMimeType("redirect", json, fn))
            }
            "local-processing" -> {
                val tunnelsArr = json.optJSONArray("tunnel") ?: JSONArray()
                val tunnels = (0 until tunnelsArr.length()).map { tunnelsArr.getString(it) }
                val output = json.optJSONObject("output")
                val fn = output?.optString("filename")
                    ?.takeIf { it.isNotBlank() }
                    ?: generatedName("media", "mp4")
                val outType = output?.optString("type")?.takeIf { it.isNotBlank() }
                    ?: guessMimeFromName(fn)
                CobaltResponse.LocalProcessing(
                    type = json.optString("type", "merge"),
                    tunnels = tunnels,
                    outputFilename = fn,
                    outputMimeType = outType,
                    service = json.optString("service", "")
                )
            }
            "picker" -> {
                val arr = json.optJSONArray("picker") ?: JSONArray()
                val items = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    PickerItem(
                        type = o.optString("type", "photo"),
                        url = o.getString("url"),
                        thumb = o.optString("thumb").takeIf { t -> t.isNotBlank() }
                    )
                }
                CobaltResponse.Picker(
                    items = items,
                    backgroundAudio = json.optString("audio").takeIf { it.isNotBlank() },
                    backgroundAudioFilename = json.optString("audioFilename").takeIf { it.isNotBlank() }
                )
            }
            "error" -> {
                val errObj = json.optJSONObject("error")
                val code = errObj?.optString("code") ?: json.optString("text", "api.generic")
                val ctxObj = errObj?.optJSONObject("context")
                val ctx = ctxObj?.let { obj ->
                    obj.keys().asSequence().associateWith { k -> obj.opt(k) }
                }
                CobaltResponse.CobaltError(code, ctx)
            }
            else -> CobaltResponse.CobaltError("api.unknown_response", null)
        }
    }

    /** Resolves a MIME type from contentDisposition-ish filename or response hints. */
    private fun getMimeType(status: String, json: JSONObject, filename: String): String {
        json.optString("mimeType").takeIf { it.isNotBlank() }?.let { return it }
        return guessMimeFromName(filename)
    }

    private fun guessMimeFromName(name: String): String = when {
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".opus", true) -> "audio/opus"
        name.endsWith(".ogg", true) -> "audio/ogg"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        else -> "application/octet-stream"
    }

    private fun generatedName(prefix: String, ext: String): String =
        "${prefix}_${System.currentTimeMillis()}.$ext"
}
```

- [ ] Step 3: Write `app\src\test\java\com\andrometa\pullout\CobaltResponseTest.kt` (parses via reflection of the same `parse` logic by exercising public surface — here we test the sealed types directly and a small JSON-shape helper).
```kotlin
package com.andrometa.pullout

import com.andrometa.pullout.api.CobaltResponse
import com.andrometa.pullout.api.PickerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CobaltResponseTest {
    @Test fun tunnelHoldsFields() {
        val r = CobaltResponse.Tunnel("https://t/1", "a.mp4", "video/mp4")
        assertEquals("a.mp4", r.filename)
        assertEquals("video/mp4", r.mimeType)
    }

    @Test fun localProcessingHoldsTunnels() {
        val r = CobaltResponse.LocalProcessing(
            type = "merge",
            tunnels = listOf("https://t/v", "https://t/a"),
            outputFilename = "out.mp4",
            outputMimeType = "video/mp4",
            service = "youtube"
        )
        assertEquals(2, r.tunnels.size)
        assertEquals("merge", r.type)
    }

    @Test fun pickerHoldsItems() {
        val r = CobaltResponse.Picker(
            items = listOf(PickerItem("photo", "https://i/1", "https://i/t")),
            backgroundAudio = null,
            backgroundAudioFilename = null
        )
        assertEquals(1, r.items.size)
        assertEquals("photo", r.items[0].type)
    }

    @Test fun errorHoldsCode() {
        val r = CobaltResponse.CobaltError("error.api.content.too_long", mapOf("limit" to 7200))
        assertEquals("error.api.content.too_long", r.code)
        assertTrue(r.context!!.containsKey("limit"))
    }
}
```

- [ ] Step 4: Run tests.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:testDebugUnitTest --tests "com.andrometa.pullout.CobaltResponseTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] Step 5: Commit.
```bash
git add -A && git commit -m "feat: CobaltResponse sealed class + CobaltApiClient JSON parsing"
```

---

### Task 6: ServerState + NodeServerManager + CobaltServerService

**Files:** Create `app\src\main\java\com\andrometa\pullout\server\{ServerState.kt,NodeServerManager.kt,CobaltServerService.kt}`

> Replace `NODE_CLASS` usages below with the FQ class recorded in Task 1 Step 5. The code uses the staltz fork default `com.staltz.nodejsmobile.NodeJsMobile.startNodeWithArguments(String[])`. If the inspected class differs, change the import and the call accordingly. The method blocks until node exits, so it MUST run on a dedicated background thread.

- [ ] Step 1: Write `server\ServerState.kt`.
```kotlin
package com.andrometa.pullout.server

sealed class ServerState {
    data object Cold : ServerState()
    data class Warming(val progress: Int) : ServerState()
    data object Ready : ServerState()
    data class Error(val message: String) : ServerState()
}
```

- [ ] Step 2: Confirm the node startup symbol once more before writing the manager.
```powershell
cd D:\Projects\PULLOUT
$tmp = "$env:TEMP\pullout-aar"
javap -classpath (Join-Path $tmp "classes.jar") com.staltz.nodejsmobile.NodeJsMobile 2>$null
```
Expected: shows `public static int startNodeWithArguments(java.lang.String[])`. If the class path differs, use the FQ name found in Task 1 and adjust the import in Step 3.

- [ ] Step 3: Write `server\NodeServerManager.kt`.
```kotlin
package com.andrometa.pullout.server

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.staltz.nodejsmobile.NodeJsMobile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object NodeServerManager {

    private const val PROJECT_DIR = "nodejs-project"
    private const val VERSION_FILE = "pullout-version.txt"
    private const val HEALTH_URL = "http://127.0.0.1:9000/"
    private const val HEALTH_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 500L

    private val _serverState = MutableLiveData<ServerState>(ServerState.Cold)
    val serverState: LiveData<ServerState> = _serverState

    @Volatile var currentOriginalUrl: String = ""

    private val nodeStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .build()

    fun isReady(): Boolean = _serverState.value is ServerState.Ready

    fun startServer(context: Context) {
        if (nodeStarted.getAndSet(true)) {
            // Already started; if it later became ready we keep state, otherwise re-poll.
            if (!isReady()) scope.launch { pollHealth() }
            return
        }
        _serverState.postValue(ServerState.Warming(0))
        scope.launch {
            try {
                val projectRoot = ensureProjectExtracted(context)
                // node entrypoint is main.js at project root
                val mainJs = File(projectRoot, "main.js").absolutePath
                // Start node on a dedicated thread; startNodeWithArguments blocks.
                Thread({
                    NodeJsMobile.startNodeWithArguments(arrayOf("node", mainJs))
                }, "nodejs-mobile").apply { isDaemon = true; start() }

                pollHealth()
            } catch (e: Exception) {
                _serverState.postValue(ServerState.Error(e.message ?: "node start failed"))
            }
        }
    }

    fun restartServer(context: Context) {
        // nodejs-mobile cannot cleanly restart the in-process node; surface a warming state
        // and re-poll. A full process kill is the only true restart, handled by the OS.
        _serverState.postValue(ServerState.Warming(0))
        scope.launch { pollHealth() }
    }

    private suspend fun pollHealth() {
        val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val elapsed = HEALTH_TIMEOUT_MS - (deadline - System.currentTimeMillis())
            val pct = ((elapsed * 100) / HEALTH_TIMEOUT_MS).toInt().coerceIn(0, 99)
            _serverState.postValue(ServerState.Warming(pct))
            if (ping()) {
                _serverState.postValue(ServerState.Ready)
                return
            }
            delay(POLL_INTERVAL_MS)
        }
        _serverState.postValue(ServerState.Error("server did not become ready"))
    }

    private suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(HEALTH_URL).get().build()
            healthClient.newCall(req).execute().use { it.isSuccessful || it.code == 200 }
        } catch (e: Exception) {
            false
        }
    }

    /** Copies nodejs-project from assets to filesDir when the bundled version differs. */
    private fun ensureProjectExtracted(context: Context): File {
        val target = File(context.filesDir, PROJECT_DIR)
        val bundledVersion = readAssetVersion(context)
        val installedVersionFile = File(target, VERSION_FILE)
        val installedVersion =
            if (installedVersionFile.exists()) installedVersionFile.readText().trim() else ""

        if (target.exists() && installedVersion == bundledVersion && bundledVersion.isNotEmpty()) {
            return target
        }
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        copyAssetDir(context, PROJECT_DIR, target)
        return target
    }

    private fun readAssetVersion(context: Context): String =
        try {
            context.assets.open("$PROJECT_DIR/$VERSION_FILE").bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            ""
        }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // It's a file
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childDest = File(dest, child)
            val grandChildren = context.assets.list(childAsset) ?: emptyArray()
            if (grandChildren.isEmpty()) {
                // could be empty dir or a file; try opening as a file
                try {
                    context.assets.open(childAsset).use { input ->
                        FileOutputStream(childDest).use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    childDest.mkdirs()
                }
            } else {
                copyAssetDir(context, childAsset, childDest)
            }
        }
    }
}
```

- [ ] Step 4: Write `server\CobaltServerService.kt`.
```kotlin
package com.andrometa.pullout.server

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.andrometa.pullout.util.NotificationHelper
import com.andrometa.pullout.R

class CobaltServerService : Service() {

    private lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: startForeground() must run on the main thread before any coroutine launches.
        val notification = notificationHelper.buildForegroundNotification(
            getString(R.string.server_running)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.FOREGROUND_ID, notification)
        }

        // Boot the node server (idempotent; manager guards against double-start).
        NodeServerManager.startServer(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // node runs in-process on a daemon thread; nothing to cancel here beyond the FGS.
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

- [ ] Step 5: Build to verify the AAR class resolves.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If it fails with `unresolved reference: NodeJsMobile`, fix the import to the FQ class from Task 1 Step 5 and rebuild.

- [ ] Step 6: Commit.
```bash
git add -A && git commit -m "feat: ServerState + NodeServerManager + CobaltServerService"
```

---

### Task 7: MediaStoreWriter + DownloadRepository + LocalMuxingManager

**Files:** Create `app\src\main\java\com\andrometa\pullout\download\{MediaStoreWriter.kt,DownloadRepository.kt,LocalMuxingManager.kt}`

- [ ] Step 1: Write `download\MediaStoreWriter.kt`.
```kotlin
package com.andrometa.pullout.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

class MediaStoreWriter(private val context: Context) {

    private val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/PULLOUT"

    /** Creates a pending MediaStore entry under Download/PULLOUT and returns its uri. */
    fun open(filename: String, mimeType: String): Uri {
        val safe = sanitize(filename)
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else
                MediaStore.Files.getContentUri("external")

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert returned null")
    }

    /** Clears IS_PENDING so the file becomes visible. */
    fun finalize(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    fun delete(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun openOutputStream(uri: Uri) = context.contentResolver.openOutputStream(uri)

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(180).ifBlank { "pullout_${System.currentTimeMillis()}" }
}
```

- [ ] Step 2: Write `download\DownloadRepository.kt`.
```kotlin
package com.andrometa.pullout.download

import android.content.Context
import androidx.lifecycle.LiveData

class DownloadRepository(context: Context) {

    private val dao = DownloadDatabase.get(context).downloadDao()

    val allDownloads: LiveData<List<DownloadRecord>> = dao.getAllLive()
    val activeDownloads: LiveData<List<DownloadRecord>> = dao.getActiveLive()

    suspend fun insert(record: DownloadRecord): Long = dao.insert(record)
    suspend fun updateStatus(id: Long, status: DownloadStatus) = dao.updateStatus(id, status)
    suspend fun updateProgress(id: Long, bytes: Long, total: Long) = dao.updateProgress(id, bytes, total)
    suspend fun updateMediaStoreUri(id: Long, uri: String) = dao.updateMediaStoreUri(id, uri)
    suspend fun updateMuxPhase(id: Long, phase: String) = dao.updateMuxPhase(id, phase)
    suspend fun incrementRetry(id: Long) = dao.incrementRetry(id)
    suspend fun getById(id: Long): DownloadRecord? = dao.getById(id)
    suspend fun clearHistory() = dao.clearHistory()
    suspend fun resetStuckDownloads() = dao.resetStuckDownloads()
}
```

- [ ] Step 3: Write `download\LocalMuxingManager.kt`.
```kotlin
package com.andrometa.pullout.download

import android.content.Context
import com.andrometa.pullout.api.CobaltResponse
import com.andrometa.pullout.util.NotificationHelper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class LocalMuxingManager(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Downloads the local-processing tunnels, muxes them with ffmpeg-kit, and writes the
     * result to MediaStore. All temp files are deleted in the outer finally block.
     */
    suspend fun process(
        context: Context,
        record: DownloadRecord,
        response: CobaltResponse.LocalProcessing,
        repository: DownloadRepository,
        notificationHelper: NotificationHelper,
        mediaStoreWriter: MediaStoreWriter
    ): Boolean {
        val cacheDir = context.cacheDir
        val videoTmp = File(cacheDir, "pullout_v_${record.id}.tmp")
        val audioTmp = File(cacheDir, "pullout_a_${record.id}.tmp")
        val outputTmp = File(cacheDir, "pullout_out_${record.id}.tmp")

        try {
            // Phase 1: download primary (video) stream
            repository.updateMuxPhase(record.id, "DOWNLOADING_VIDEO")
            downloadTo(response.tunnels[0], videoTmp) { read, total ->
                repository.updateProgress(record.id, read, total)
                notificationHelper.updateProgress(record.id, read, total)
            }

            val hasAudio = response.tunnels.size > 1
            // Phase 2: optional audio stream
            if (hasAudio) {
                repository.updateMuxPhase(record.id, "DOWNLOADING_AUDIO")
                downloadTo(response.tunnels[1], audioTmp) { read, total ->
                    repository.updateProgress(record.id, read, total)
                    notificationHelper.updateProgress(record.id, read, total)
                }
            }

            // Phase 3: mux
            repository.updateMuxPhase(record.id, "MERGING")
            val cmd = if (hasAudio) {
                "-y -i \"${videoTmp.absolutePath}\" -i \"${audioTmp.absolutePath}\" " +
                    "-c:v copy -c:a copy -movflags +faststart \"${outputTmp.absolutePath}\""
            } else {
                "-y -i \"${videoTmp.absolutePath}\" -c copy -movflags +faststart \"${outputTmp.absolutePath}\""
            }

            val success = runFfmpeg(cmd)
            if (!success || !outputTmp.exists() || outputTmp.length() == 0L) {
                notificationHelper.showFailed(record.id, record.filename)
                return false
            }

            // Write muxed output to MediaStore
            val uri = mediaStoreWriter.open(record.outputFilenameOrDefault(), record.mimeType)
            mediaStoreWriter.openOutputStream(uri).use { out ->
                if (out == null) throw IllegalStateException("null MediaStore output stream")
                outputTmp.inputStream().use { it.copyTo(out) }
            }
            mediaStoreWriter.finalize(uri)
            repository.updateMediaStoreUri(record.id, uri.toString())
            repository.updateMuxPhase(record.id, "")
            notificationHelper.showComplete(record.id, record.filename, uri, record.mimeType)
            return true
        } finally {
            // Always clean up temp files regardless of outcome.
            videoTmp.delete()
            audioTmp.delete()
            outputTmp.delete()
        }
    }

    private fun DownloadRecord.outputFilenameOrDefault(): String =
        filename.ifBlank { "pullout_${System.currentTimeMillis()}.mp4" }

    private fun downloadTo(
        url: String,
        dest: File,
        onProgress: (read: Long, total: Long) -> Unit
    ) {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IllegalStateException("empty body for $url")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var sum = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        sum += read
                        onProgress(sum, total)
                    }
                }
            }
        }
    }

    private suspend fun runFfmpeg(command: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeAsync(command) { s ->
                cont.resume(ReturnCode.isSuccess(s.returnCode))
            }
            cont.invokeOnCancellation { session.cancel() }
        }
}
```

- [ ] Step 4: Build.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 5: Commit.
```bash
git add -A && git commit -m "feat: MediaStoreWriter, DownloadRepository, LocalMuxingManager (ffmpeg-kit)"
```

---

### Task 8: DownloadService + RetryDownloadWorker

**Files:** Create `app\src\main\java\com\andrometa\pullout\download\{DownloadService.kt,RetryDownloadWorker.kt}`

- [ ] Step 1: Write `download\DownloadService.kt`.
```kotlin
package com.andrometa.pullout.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.andrometa.pullout.R
import com.andrometa.pullout.api.CobaltResponse
import com.andrometa.pullout.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: DownloadRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var mediaStoreWriter: MediaStoreWriter
    private val muxingManager = LocalMuxingManager()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository(this)
        notificationHelper = NotificationHelper(this)
        mediaStoreWriter = MediaStoreWriter(this)
        notificationHelper.createChannel()
        scope.launch { repository.resetStuckDownloads() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: startForeground() on main thread before any coroutine launch.
        val notification = notificationHelper.buildForegroundNotification(
            getString(R.string.notif_downloading)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.FOREGROUND_ID, notification)
        }

        when (intent?.action) {
            ACTION_DIRECT -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                val filename = intent.getStringExtra(EXTRA_FILENAME).orEmpty()
                val mimeType = intent.getStringExtra(EXTRA_MIME).orEmpty()
                val originalUrl = intent.getStringExtra(EXTRA_ORIGINAL).orEmpty()
                scope.launch { handleDirect(url, filename, mimeType, originalUrl) }
            }
            ACTION_MUX -> {
                val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1)
                scope.launch { handleMux(recordId) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun handleDirect(
        url: String, filename: String, mimeType: String, originalUrl: String
    ) {
        val recordId = repository.insert(
            DownloadRecord(
                originalUrl = originalUrl,
                cobaltUrl = url,
                filename = filename,
                mimeType = mimeType,
                status = DownloadStatus.DOWNLOADING
            )
        )
        var uri = android.net.Uri.EMPTY
        try {
            uri = mediaStoreWriter.open(filename, mimeType)
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty body")
                val total = body.contentLength()
                mediaStoreWriter.openOutputStream(uri).use { out ->
                    if (out == null) throw IOException("null output stream")
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var sum = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            sum += read
                            repository.updateProgress(recordId, sum, total)
                            notificationHelper.updateProgress(recordId, sum, total)
                        }
                    }
                }
            }
            mediaStoreWriter.finalize(uri)
            repository.updateMediaStoreUri(recordId, uri.toString())
            repository.updateStatus(recordId, DownloadStatus.COMPLETE)
            notificationHelper.showComplete(recordId, filename, uri, mimeType)
        } catch (e: Exception) {
            if (uri != android.net.Uri.EMPTY) mediaStoreWriter.delete(uri)
            handleNetworkFail(recordId, originalUrl, filename, e)
        } finally {
            stopIfIdle()
        }
    }

    private suspend fun handleMux(recordId: Long) {
        val record = repository.getById(recordId)
        if (record == null) { stopIfIdle(); return }
        repository.updateStatus(recordId, DownloadStatus.DOWNLOADING)
        try {
            val tunnels = record.tempFilePath
                .split("")
                .filter { it.isNotBlank() }
            val response = CobaltResponse.LocalProcessing(
                type = record.muxPhase.ifBlank { "merge" },
                tunnels = tunnels,
                outputFilename = record.filename,
                outputMimeType = record.mimeType,
                service = ""
            )
            val ok = muxingManager.process(
                this, record, response, repository, notificationHelper, mediaStoreWriter
            )
            repository.updateStatus(
                recordId, if (ok) DownloadStatus.COMPLETE else DownloadStatus.FAILED
            )
        } catch (e: Exception) {
            handleNetworkFail(recordId, record.originalUrl, record.filename, e)
        } finally {
            stopIfIdle()
        }
    }

    private suspend fun handleNetworkFail(
        recordId: Long, originalUrl: String, filename: String, e: Exception
    ) {
        val isStorageFull = e.message?.contains("ENOSPC", true) == true ||
            e.cause?.message?.contains("ENOSPC", true) == true
        if (isStorageFull) {
            repository.updateStatus(recordId, DownloadStatus.FAILED)
            notificationHelper.showStorageFull()
            return
        }
        val isNetwork = e is IOException || e is InterruptedIOException
        val record = repository.getById(recordId)
        val retryCount = record?.retryCount ?: 0
        if (isNetwork && retryCount < MAX_RETRIES) {
            repository.incrementRetry(recordId)
            repository.updateStatus(recordId, DownloadStatus.FAILED_NETWORK)
            RetryDownloadWorker.enqueue(this, recordId, originalUrl, filename)
            notificationHelper.showRetryReady(recordId, originalUrl, filename)
        } else {
            repository.updateStatus(recordId, DownloadStatus.FAILED)
            notificationHelper.showFailed(recordId, filename)
        }
    }

    private fun stopIfIdle() {
        // Stop the foreground service once no work remains in this process tick.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_DIRECT = "com.andrometa.pullout.action.DIRECT"
        const val ACTION_MUX = "com.andrometa.pullout.action.MUX"
        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_MIME = "mime"
        const val EXTRA_ORIGINAL = "original"
        const val EXTRA_RECORD_ID = "record_id"
        const val MAX_RETRIES = 3

        fun startDirect(
            context: Context, url: String, filename: String, mimeType: String, originalUrl: String
        ) {
            val i = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DIRECT
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_MIME, mimeType)
                putExtra(EXTRA_ORIGINAL, originalUrl)
            }
            context.startForegroundService(i)
        }

        /**
         * Inserts a muxing record (tunnels stored in tempFilePath joined by ) and
         * starts the service to process it.
         */
        suspend fun startMux(
            context: Context,
            repository: DownloadRepository,
            response: CobaltResponse.LocalProcessing,
            originalUrl: String
        ) {
            val recordId = repository.insert(
                DownloadRecord(
                    originalUrl = originalUrl,
                    cobaltUrl = response.tunnels.firstOrNull().orEmpty(),
                    filename = response.outputFilename,
                    mimeType = response.outputMimeType,
                    status = DownloadStatus.QUEUED,
                    isMuxed = true,
                    muxPhase = response.type,
                    tempFilePath = response.tunnels.joinToString("")
                )
            )
            val i = Intent(context, DownloadService::class.java).apply {
                action = ACTION_MUX
                putExtra(EXTRA_RECORD_ID, recordId)
            }
            context.startForegroundService(i)
        }
    }
}
```

- [ ] Step 2: Write `download\RetryDownloadWorker.kt`.
```kotlin
package com.andrometa.pullout.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.andrometa.pullout.api.CobaltApiClient
import com.andrometa.pullout.api.CobaltResponse
import com.andrometa.pullout.util.NotificationHelper
import com.andrometa.pullout.util.SettingsRepository
import java.util.concurrent.TimeUnit

class RetryDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1)
        val originalUrl = inputData.getString(KEY_ORIGINAL_URL).orEmpty()
        val filename = inputData.getString(KEY_FILENAME).orEmpty()
        if (recordId < 0 || originalUrl.isBlank()) return Result.failure()

        val repository = DownloadRepository(applicationContext)
        val record = repository.getById(recordId) ?: return Result.failure()

        // Service guard: never retry beyond MAX.
        if (record.retryCount >= DownloadService.MAX_RETRIES) {
            NotificationHelper(applicationContext).showFailed(recordId, filename)
            return Result.failure()
        }

        val settings = SettingsRepository(applicationContext)
        return try {
            val response = CobaltApiClient.submit(
                settings.cobaltInstanceUrl,
                originalUrl,
                settings.audioOnlyMode,
                settings.defaultQuality
            )
            when (response) {
                is CobaltResponse.Tunnel ->
                    DownloadService.startDirect(
                        applicationContext, response.url, response.filename, response.mimeType, originalUrl
                    )
                is CobaltResponse.Redirect ->
                    DownloadService.startDirect(
                        applicationContext, response.url, response.filename, response.mimeType, originalUrl
                    )
                is CobaltResponse.LocalProcessing ->
                    DownloadService.startMux(applicationContext, repository, response, originalUrl)
                else -> {
                    NotificationHelper(applicationContext).showFailed(recordId, filename)
                    return Result.failure()
                }
            }
            NotificationHelper(applicationContext).showRetryReady(recordId, originalUrl, filename)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_RECORD_ID = "record_id"
        const val KEY_ORIGINAL_URL = "original_url"
        const val KEY_FILENAME = "filename"

        fun enqueue(context: Context, recordId: Long, originalUrl: String, filename: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<RetryDownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_RECORD_ID to recordId,
                        KEY_ORIGINAL_URL to originalUrl,
                        KEY_FILENAME to filename
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "retry_$recordId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
```

- [ ] Step 3: Build.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 4: Commit.
```bash
git add -A && git commit -m "feat: DownloadService (direct+mux) + RetryDownloadWorker"
```

---

### Task 9: All UI Layouts (XML)

**Files:** Create `app\src\main\res\layout\{activity_main,sheet_download_queue,item_download,sheet_settings,sheet_picker,item_picker}.xml`

- [ ] Step 1: Write `layout\activity_main.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/background"
    android:padding="@dimen/padding_default">

    <Button
        android:id="@+id/btnSettings"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="@android:color/transparent"
        android:fontFamily="@font/space_mono_bold"
        android:text="@string/settings_glyph"
        android:textColor="@color/neon_cyan"
        android:textSize="22sp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

    <TextView
        android:id="@+id/tvWordmark"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:fontFamily="@font/space_mono_bold"
        android:text="@string/wordmark"
        android:textColor="@color/neon_cyan"
        android:textSize="@dimen/wordmark_size"
        android:letterSpacing="0.12"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toTopOf="@id/tvStatus"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintVertical_chainStyle="packed"
        app:layout_constraintVertical_bias="0.32"/>

    <TextView
        android:id="@+id/tvStatus"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:fontFamily="monospace"
        android:text="@string/status_init"
        android:textColor="@color/neon_amber"
        android:textSize="13sp"
        app:layout_constraintTop_toBottomOf="@id/tvWordmark"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

    <EditText
        android:id="@+id/etUrl"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="40dp"
        android:background="@drawable/bg_input_normal"
        android:fontFamily="@font/space_mono_regular"
        android:hint="@string/url_hint"
        android:imeOptions="actionDone"
        android:inputType="textUri"
        android:maxLines="1"
        android:padding="14dp"
        android:textColor="@color/text_primary"
        android:textColorHint="@color/text_dim"
        android:textSize="16sp"
        app:layout_constraintTop_toBottomOf="@id/tvStatus"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

    <Button
        android:id="@+id/btnPull"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:background="@drawable/bg_button_pull"
        android:fontFamily="@font/space_mono_bold"
        android:text="@string/pull"
        android:textColor="@color/neon_cyan"
        android:textSize="16sp"
        app:layout_constraintTop_toBottomOf="@id/etUrl"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

    <View
        android:id="@+id/viewError"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:background="@color/neon_red"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">
    </View>

    <TextView
        android:id="@+id/tvError"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:padding="12dp"
        android:fontFamily="@font/space_mono_bold"
        android:textColor="@color/background"
        android:textSize="13sp"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

    <FrameLayout
        android:id="@+id/fabQueue"
        android:layout_width="@dimen/fab_size"
        android:layout_height="@dimen/fab_size"
        android:layout_margin="8dp"
        android:background="@drawable/bg_fab"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <ImageView
            android:layout_width="22dp"
            android:layout_height="22dp"
            android:layout_gravity="center"
            android:src="@drawable/ic_queue"
            android:contentDescription="@string/queue_active"/>

        <TextView
            android:id="@+id/tvBadge"
            android:layout_width="@dimen/badge_size"
            android:layout_height="@dimen/badge_size"
            android:layout_gravity="top|end"
            android:background="@drawable/bg_badge"
            android:gravity="center"
            android:fontFamily="@font/space_mono_bold"
            android:textColor="@color/background"
            android:textSize="10sp"
            android:visibility="gone"/>
    </FrameLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] Step 2: Write `layout\sheet_download_queue.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@color/surface"
    android:orientation="vertical"
    android:paddingBottom="16dp">

    <View
        android:layout_width="40dp"
        android:layout_height="4dp"
        android:layout_gravity="center_horizontal"
        android:layout_marginTop="10dp"
        android:layout_marginBottom="6dp"
        android:background="@color/text_secondary"/>

    <com.google.android.material.tabs.TabLayout
        android:id="@+id/tabLayout"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:tabTextColor="@color/text_secondary"
        app:tabSelectedTextColor="@color/neon_cyan"
        app:tabIndicatorColor="@color/neon_cyan"
        app:tabTextAppearance="@style/TextAppearance.Pullout.MonoBold">
        <com.google.android.material.tabs.TabItem
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/queue_active"/>
        <com.google.android.material.tabs.TabItem
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/queue_history"/>
    </com.google.android.material.tabs.TabLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerQueue"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:minHeight="240dp"
        android:clipToPadding="false"
        android:paddingTop="8dp"/>

    <TextView
        android:id="@+id/tvEmpty"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:padding="40dp"
        android:fontFamily="@font/space_mono_regular"
        android:text="@string/queue_empty"
        android:textColor="@color/text_secondary"
        android:textSize="14sp"
        android:visibility="gone"/>

</LinearLayout>
```

- [ ] Step 3: Write `layout\item_download.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingHorizontal="16dp"
    android:paddingVertical="10dp">

    <TextView
        android:id="@+id/tvFilename"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:ellipsize="middle"
        android:fontFamily="@font/space_mono_regular"
        android:singleLine="true"
        android:textColor="@color/text_primary"
        android:textSize="14sp"/>

    <TextView
        android:id="@+id/tvStatus"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:fontFamily="@font/space_mono_regular"
        android:textColor="@color/text_secondary"
        android:textSize="12sp"/>

    <TextView
        android:id="@+id/tvMuxPhase"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:fontFamily="@font/space_mono_regular"
        android:textColor="@color/text_secondary"
        android:textSize="11sp"
        android:visibility="gone"/>

    <ProgressBar
        android:id="@+id/progressBar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="@dimen/progress_bar_height"
        android:layout_marginTop="6dp"
        android:progressTint="@color/neon_cyan"
        android:progressBackgroundTint="@color/border"/>

    <LinearLayout
        android:id="@+id/layoutActions"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        android:orientation="horizontal">

        <Button
            android:id="@+id/btnOpen"
            style="@style/Widget.Pullout.OutlinedButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:minWidth="0dp"
            android:textSize="12sp"
            android:text="@string/action_open"/>

        <Button
            android:id="@+id/btnShare"
            style="@style/Widget.Pullout.OutlinedButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:minWidth="0dp"
            android:textSize="12sp"
            android:text="@string/action_share"/>

        <Button
            android:id="@+id/btnRetry"
            style="@style/Widget.Pullout.OutlinedButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:minWidth="0dp"
            android:textSize="12sp"
            android:text="@string/action_retry"/>

        <Button
            android:id="@+id/btnCancel"
            style="@style/Widget.Pullout.OutlinedButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:minWidth="0dp"
            android:textSize="12sp"
            android:text="@string/action_cancel"/>
    </LinearLayout>

</LinearLayout>
```

- [ ] Step 4: Write `layout\sheet_settings.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@color/surface_elevated">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="20dp">

        <View
            android:layout_width="40dp"
            android:layout_height="4dp"
            android:layout_gravity="center_horizontal"
            android:layout_marginBottom="14dp"
            android:background="@color/text_secondary"/>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:fontFamily="@font/space_mono_bold"
            android:text="@string/settings_title"
            android:textColor="@color/neon_cyan"
            android:textSize="18sp"/>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:fontFamily="@font/space_mono_regular"
            android:text="@string/settings_cobalt_url"
            android:textColor="@color/text_secondary"
            android:textSize="12sp"/>

        <EditText
            android:id="@+id/etCobaltUrl"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:background="@drawable/bg_input_normal"
            android:fontFamily="@font/space_mono_regular"
            android:inputType="textUri"
            android:maxLines="1"
            android:padding="12dp"
            android:textColor="@color/text_primary"
            android:textSize="14sp"/>

        <com.google.android.material.materialswitch.MaterialSwitch
            android:id="@+id/switchAudioOnly"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:fontFamily="@font/space_mono_regular"
            android:text="@string/settings_audio_only"
            android:textColor="@color/text_primary"/>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:fontFamily="@font/space_mono_regular"
            android:text="@string/settings_quality"
            android:textColor="@color/text_secondary"
            android:textSize="12sp"/>

        <RadioGroup
            android:id="@+id/radioQuality"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:orientation="horizontal">
            <RadioButton android:id="@+id/qMax" android:layout_width="wrap_content"
                android:layout_height="wrap_content" android:text="MAX"
                android:fontFamily="@font/space_mono_regular" android:textColor="@color/text_primary"/>
            <RadioButton android:id="@+id/q1080" android:layout_width="wrap_content"
                android:layout_height="wrap_content" android:text="1080"
                android:fontFamily="@font/space_mono_regular" android:textColor="@color/text_primary"/>
            <RadioButton android:id="@+id/q720" android:layout_width="wrap_content"
                android:layout_height="wrap_content" android:text="720"
                android:fontFamily="@font/space_mono_regular" android:textColor="@color/text_primary"/>
            <RadioButton android:id="@+id/q480" android:layout_width="wrap_content"
                android:layout_height="wrap_content" android:text="480"
                android:fontFamily="@font/space_mono_regular" android:textColor="@color/text_primary"/>
        </RadioGroup>

        <Button
            android:id="@+id/btnBattery"
            style="@style/Widget.Pullout.OutlinedButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:text="@string/settings_battery"/>

        <Button
            android:id="@+id/btnCobaltDocs"
            style="@style/Widget.Pullout.OutlinedButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:text="@string/settings_docs"/>

        <Button
            android:id="@+id/btnClearHistory"
            style="@style/Widget.Pullout.OutlinedButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:textColor="@color/neon_red"
            app:strokeColor="@color/neon_red"
            android:text="@string/settings_clear_history"/>

    </LinearLayout>
</ScrollView>
```

- [ ] Step 5: Write `layout\sheet_picker.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@color/surface"
    android:orientation="vertical"
    android:padding="16dp">

    <View
        android:layout_width="40dp"
        android:layout_height="4dp"
        android:layout_gravity="center_horizontal"
        android:layout_marginBottom="12dp"
        android:background="@color/text_secondary"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:fontFamily="@font/space_mono_bold"
        android:text="@string/picker_title"
        android:textColor="@color/text_primary"
        android:textSize="15sp"/>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerPicker"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:minHeight="300dp"
        android:layout_marginTop="12dp"/>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:orientation="horizontal">

        <Button
            android:id="@+id/btnPullAll"
            style="@style/Widget.Pullout.OutlinedButton"
            android:layout_width="0dp"
            android:layout_weight="1"
            android:layout_height="wrap_content"
            android:text="@string/action_pull_all"/>

        <Button
            android:id="@+id/btnPullSelected"
            style="@style/Widget.Pullout.OutlinedButton"
            android:layout_width="0dp"
            android:layout_weight="1"
            android:layout_height="wrap_content"
            android:layout_marginStart="10dp"
            android:text="@string/action_pull_selected"/>
    </LinearLayout>

</LinearLayout>
```

- [ ] Step 6: Write `layout\item_picker.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="0dp"
    app:layout_constraintDimensionRatio="1:1"
    android:layout_margin="4dp">

    <ImageView
        android:id="@+id/imgThumb"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:scaleType="centerCrop"
        android:background="@color/surface_elevated"
        android:contentDescription="@string/picker_title"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

    <View
        android:id="@+id/selectedOverlay"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:background="@color/cyan_30"
        android:visibility="gone"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

    <ImageView
        android:id="@+id/checkIcon"
        android:layout_width="22dp"
        android:layout_height="22dp"
        android:layout_margin="6dp"
        android:src="@drawable/ic_queue"
        android:visibility="gone"
        app:tint="@color/neon_cyan"
        android:contentDescription="@string/action_pull_selected"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] Step 7: Build resources.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:processDebugResources
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 8: Commit.
```bash
git add -A && git commit -m "feat: all UI layouts (main, queue, item, settings, picker)"
```

---

### Task 10: DownloadQueueViewModel + DownloadAdapter + DownloadQueueSheet

**Files:** Create `app\src\main\java\com\andrometa\pullout\ui\{DownloadQueueViewModel.kt,DownloadAdapter.kt,DownloadQueueSheet.kt}`

- [ ] Step 1: Write `ui\DownloadQueueViewModel.kt`.
```kotlin
package com.andrometa.pullout.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.andrometa.pullout.download.DownloadRecord
import com.andrometa.pullout.download.DownloadRepository
import kotlinx.coroutines.launch

class DownloadQueueViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DownloadRepository(app)

    val allDownloads: LiveData<List<DownloadRecord>> = repo.allDownloads
    val activeDownloads: LiveData<List<DownloadRecord>> = repo.activeDownloads

    fun clearHistory() {
        viewModelScope.launch { repo.clearHistory() }
    }
}
```

- [ ] Step 2: Write `ui\DownloadAdapter.kt`.
```kotlin
package com.andrometa.pullout.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.andrometa.pullout.databinding.ItemDownloadBinding
import com.andrometa.pullout.download.DownloadRecord
import com.andrometa.pullout.download.DownloadStatus

class DownloadAdapter(
    private val onRetry: (DownloadRecord) -> Unit,
    private val onCancel: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, DownloadAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    inner class VH(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(r: DownloadRecord) {
            val ctx = b.root.context
            b.tvFilename.text = r.filename

            // Reset all conditional views before binding.
            b.btnOpen.visibility = View.GONE
            b.btnShare.visibility = View.GONE
            b.btnRetry.visibility = View.GONE
            b.btnCancel.visibility = View.GONE
            b.tvMuxPhase.visibility = View.GONE
            b.progressBar.visibility = View.VISIBLE

            when (r.status) {
                DownloadStatus.QUEUED -> {
                    b.tvStatus.text = "queued"
                    b.tvStatus.setTextColor(ctx.colorOf("text_secondary"))
                    b.progressBar.isIndeterminate = true
                    b.btnCancel.visibility = View.VISIBLE
                }
                DownloadStatus.DOWNLOADING -> {
                    b.tvStatus.text = "downloading"
                    b.tvStatus.setTextColor(ctx.colorOf("neon_cyan"))
                    if (r.totalBytes > 0) {
                        b.progressBar.isIndeterminate = false
                        b.progressBar.max = 100
                        b.progressBar.progress =
                            ((r.bytesDownloaded * 100) / r.totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        b.progressBar.isIndeterminate = true
                    }
                    if (r.isMuxed && r.muxPhase.isNotBlank()) {
                        b.tvMuxPhase.visibility = View.VISIBLE
                        b.tvMuxPhase.text = r.muxPhase.lowercase().replace('_', ' ')
                    }
                    b.btnCancel.visibility = View.VISIBLE
                }
                DownloadStatus.COMPLETE -> {
                    b.tvStatus.text = "complete"
                    b.tvStatus.setTextColor(ctx.colorOf("neon_green"))
                    b.progressBar.visibility = View.GONE
                    b.btnOpen.visibility = View.VISIBLE
                    b.btnShare.visibility = View.VISIBLE
                    b.btnOpen.setOnClickListener { open(ctx, r) }
                    b.btnShare.setOnClickListener { share(ctx, r) }
                }
                DownloadStatus.FAILED, DownloadStatus.FAILED_NETWORK -> {
                    b.tvStatus.text = "failed"
                    b.tvStatus.setTextColor(ctx.colorOf("neon_red"))
                    b.progressBar.visibility = View.GONE
                    b.btnRetry.visibility = View.VISIBLE
                    b.btnRetry.setOnClickListener { onRetry(r) }
                }
            }
            b.btnCancel.setOnClickListener { onCancel(r) }
        }

        private fun open(ctx: Context, r: DownloadRecord) {
            if (r.mediaStoreUriString.isBlank()) return
            val uri = Uri.parse(r.mediaStoreUriString)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, r.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "Open"))
        }

        private fun share(ctx: Context, r: DownloadRecord) {
            if (r.mediaStoreUriString.isBlank()) return
            val uri = Uri.parse(r.mediaStoreUriString)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = r.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "Share"))
        }
    }

    private fun Context.colorOf(name: String): Int {
        val id = resources.getIdentifier(name, "color", packageName)
        return resources.getColor(id, theme)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadRecord>() {
            override fun areItemsTheSame(a: DownloadRecord, b: DownloadRecord) = a.id == b.id
            override fun areContentsTheSame(a: DownloadRecord, b: DownloadRecord) = a == b
        }
    }
}
```

- [ ] Step 3: Write `ui\DownloadQueueSheet.kt`.
```kotlin
package com.andrometa.pullout.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.andrometa.pullout.databinding.SheetDownloadQueueBinding
import com.andrometa.pullout.download.DownloadRecord
import com.andrometa.pullout.download.DownloadStatus
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout

class DownloadQueueSheet : BottomSheetDialogFragment() {

    private var _binding: SheetDownloadQueueBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DownloadQueueViewModel by activityViewModels()

    var onRetry: ((DownloadRecord) -> Unit)? = null
    var onCancel: ((DownloadRecord) -> Unit)? = null

    private var currentTab = 0

    private val adapter by lazy {
        DownloadAdapter(
            onRetry = { onRetry?.invoke(it) },
            onCancel = { onCancel?.invoke(it) }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetDownloadQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerQueue.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerQueue.adapter = adapter

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        viewModel.allDownloads.observe(viewLifecycleOwner) { refresh() }
        viewModel.activeDownloads.observe(viewLifecycleOwner) { refresh() }
    }

    private fun refresh() {
        val list = if (currentTab == 0) {
            viewModel.activeDownloads.value.orEmpty()
        } else {
            viewModel.allDownloads.value.orEmpty().filter {
                it.status == DownloadStatus.COMPLETE ||
                    it.status == DownloadStatus.FAILED ||
                    it.status == DownloadStatus.FAILED_NETWORK
            }
        }
        adapter.submitList(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerQueue.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DownloadQueueSheet"
    }
}
```

- [ ] Step 4: Build.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 5: Commit.
```bash
git add -A && git commit -m "feat: queue ViewModel, DownloadAdapter, DownloadQueueSheet"
```

---

### Task 11: PickerAdapter + PickerSheet + SettingsSheet

**Files:** Create `app\src\main\java\com\andrometa\pullout\ui\{PickerAdapter.kt,PickerSheet.kt,SettingsSheet.kt}`

- [ ] Step 1: Write `ui\PickerAdapter.kt`.
```kotlin
package com.andrometa.pullout.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.andrometa.pullout.api.PickerItem
import com.andrometa.pullout.databinding.ItemPickerBinding

class PickerAdapter(
    private val items: List<PickerItem>
) : RecyclerView.Adapter<PickerAdapter.VH>() {

    private val selected = linkedSetOf<Int>()

    fun selectedItems(): List<PickerItem> = selected.sorted().map { items[it] }

    fun selectAll() {
        selected.clear()
        selected.addAll(items.indices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)

    inner class VH(private val b: ItemPickerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(position: Int) {
            val item = items[position]
            if (!item.thumb.isNullOrBlank()) {
                b.imgThumb.load(item.thumb)
            } else {
                b.imgThumb.setImageDrawable(null)
            }
            applySelection(position in selected)
            b.root.setOnClickListener {
                if (position in selected) selected.remove(position) else selected.add(position)
                applySelection(position in selected)
            }
        }

        private fun applySelection(isSelected: Boolean) {
            b.selectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            b.checkIcon.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }
}
```

- [ ] Step 2: Write `ui\PickerSheet.kt`.
```kotlin
package com.andrometa.pullout.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import com.andrometa.pullout.api.PickerItem
import com.andrometa.pullout.databinding.SheetPickerBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PickerSheet : BottomSheetDialogFragment() {

    private var _binding: SheetPickerBinding? = null
    private val binding get() = _binding!!

    var onItemsSelected: ((List<PickerItem>) -> Unit)? = null

    private lateinit var items: List<PickerItem>
    private lateinit var adapter: PickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        items = (arguments?.getSerializable(ARG_ITEMS) as? Array<*>)
            ?.filterIsInstance<PickerItem>()
            ?: emptyList()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = PickerAdapter(items)
        binding.recyclerPicker.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerPicker.adapter = adapter

        binding.btnPullAll.setOnClickListener {
            onItemsSelected?.invoke(items)
            dismiss()
        }
        binding.btnPullSelected.setOnClickListener {
            val sel = adapter.selectedItems()
            if (sel.isNotEmpty()) {
                onItemsSelected?.invoke(sel)
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PickerSheet"
        private const val ARG_ITEMS = "items"

        fun newInstance(items: List<PickerItem>): PickerSheet =
            PickerSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_ITEMS, ArrayList(items).toTypedArray())
                }
            }
    }
}
```

> Note: `PickerItem` must implement `java.io.Serializable` for the Bundle transport. Add `: java.io.Serializable` to its declaration in `api\CobaltResponse.kt` (Step 3 below applies this patch).

- [ ] Step 3: Patch `PickerItem` to be Serializable. Edit `api\CobaltResponse.kt`:
```kotlin
data class PickerItem(
    val type: String,
    val url: String,
    val thumb: String?
) : java.io.Serializable
```

- [ ] Step 4: Write `ui\SettingsSheet.kt`.
```kotlin
package com.andrometa.pullout.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.activityViewModels
import com.andrometa.pullout.databinding.SheetSettingsBinding
import com.andrometa.pullout.util.SettingsRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DownloadQueueViewModel by activityViewModels()
    private lateinit var settings: SettingsRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())

        binding.etCobaltUrl.setText(settings.cobaltInstanceUrl)
        binding.switchAudioOnly.isChecked = settings.audioOnlyMode

        when (settings.defaultQuality) {
            "max" -> binding.qMax.isChecked = true
            "1080" -> binding.q1080.isChecked = true
            "720" -> binding.q720.isChecked = true
            "480" -> binding.q480.isChecked = true
            else -> binding.q1080.isChecked = true
        }

        binding.switchAudioOnly.setOnCheckedChangeListener { _, isChecked ->
            settings.audioOnlyMode = isChecked
        }

        binding.radioQuality.setOnCheckedChangeListener { _, checkedId ->
            settings.defaultQuality = when (checkedId) {
                binding.qMax.id -> "max"
                binding.q1080.id -> "1080"
                binding.q720.id -> "720"
                binding.q480.id -> "480"
                else -> "1080"
            }
        }

        binding.btnBattery.setOnClickListener { openBatterySettings() }

        binding.btnCobaltDocs.setOnClickListener {
            val intent = CustomTabsIntent.Builder().build()
            intent.launchUrl(
                requireContext(),
                Uri.parse("https://github.com/imputnet/cobalt/blob/main/docs/api.md")
            )
        }

        binding.btnClearHistory.setOnClickListener {
            viewModel.clearHistory()
            dismiss()
        }
    }

    private fun openBatterySettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun onStop() {
        super.onStop()
        // CRITICAL: use _binding?. (not binding.) — view may be torn down on low memory.
        val url = _binding?.etCobaltUrl?.text?.toString()?.trim()
        if (!url.isNullOrBlank()) {
            settings.cobaltInstanceUrl = url
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsSheet"
    }
}
```

- [ ] Step 5: Build.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 6: Commit.
```bash
git add -A && git commit -m "feat: PickerAdapter, PickerSheet, SettingsSheet (+Serializable PickerItem)"
```

---

### Task 12: PulloutApplication + MainActivity

**Files:** Create `app\src\main\java\com\andrometa\pullout\{PulloutApplication.kt,MainActivity.kt}`, finalize `app\src\main\res\xml\shortcuts.xml`

- [ ] Step 1: Write `PulloutApplication.kt`.
```kotlin
package com.andrometa.pullout

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.work.Configuration
import com.andrometa.pullout.server.CobaltServerService
import com.andrometa.pullout.util.NotificationHelper

class PulloutApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper(this).createChannel()
        // Start the on-device cobalt server as a foreground service.
        val svc = Intent(this, CobaltServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
```

- [ ] Step 2: Write `MainActivity.kt`.
```kotlin
package com.andrometa.pullout

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.andrometa.pullout.api.CobaltApiClient
import com.andrometa.pullout.api.CobaltResponse
import com.andrometa.pullout.databinding.ActivityMainBinding
import com.andrometa.pullout.download.DownloadRepository
import com.andrometa.pullout.download.DownloadService
import com.andrometa.pullout.server.NodeServerManager
import com.andrometa.pullout.server.ServerState
import com.andrometa.pullout.ui.DownloadQueueSheet
import com.andrometa.pullout.ui.DownloadQueueViewModel
import com.andrometa.pullout.ui.PickerSheet
import com.andrometa.pullout.ui.SettingsSheet
import com.andrometa.pullout.util.ClipboardHelper
import com.andrometa.pullout.util.RetryBridge
import com.andrometa.pullout.util.SettingsRepository
import com.andrometa.pullout.util.UrlMatcher
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DownloadQueueViewModel by viewModels()
    private lateinit var settings: SettingsRepository
    private lateinit var repository: DownloadRepository

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository(this)
        repository = DownloadRepository(this)

        setupServerObserver()
        setupInputUi()
        setupFab()
        setupSettings()
        handleFirstLaunch()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (settings.clipboardTriggerEnabled) checkClipboard()
    }

    private fun setupServerObserver() {
        NodeServerManager.serverState.observe(this) { state ->
            when (state) {
                is ServerState.Cold, is ServerState.Warming -> {
                    binding.tvStatus.text = getString(R.string.status_init)
                    binding.tvStatus.setTextColor(color(R.color.neon_amber))
                    startPulse(binding.tvStatus)
                    binding.btnPull.isEnabled = false
                }
                is ServerState.Ready -> {
                    binding.tvStatus.text = getString(R.string.status_ready)
                    binding.tvStatus.setTextColor(color(R.color.neon_green))
                    stopPulse(binding.tvStatus)
                    binding.btnPull.isEnabled = true
                }
                is ServerState.Error -> {
                    binding.tvStatus.text = getString(R.string.status_offline)
                    binding.tvStatus.setTextColor(color(R.color.neon_red))
                    stopPulse(binding.tvStatus)
                    binding.btnPull.isEnabled = false
                    Snackbar.make(binding.root, R.string.err_offline, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_retry) {
                            NodeServerManager.restartServer(applicationContext)
                        }.show()
                }
            }
        }
    }

    private fun setupInputUi() {
        binding.btnPull.setOnClickListener {
            submitUrl(binding.etUrl.text?.toString())
        }
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            binding.etUrl.setBackgroundResource(
                if (hasFocus) R.drawable.bg_input_focused else R.drawable.bg_input_normal
            )
        }
        binding.etUrl.setOnEditorActionListener { _, _, _ ->
            submitUrl(binding.etUrl.text?.toString())
            true
        }
    }

    private fun setupFab() {
        binding.fabQueue.setOnClickListener { openQueue() }
        viewModel.activeDownloads.observe(this) { active ->
            val count = active.size
            if (count > 0) {
                binding.tvBadge.visibility = View.VISIBLE
                binding.tvBadge.text = count.toString()
            } else {
                binding.tvBadge.visibility = View.GONE
            }
        }
    }

    private fun setupSettings() {
        binding.btnSettings.setOnClickListener {
            SettingsSheet().show(supportFragmentManager, SettingsSheet.TAG)
        }
    }

    private fun openQueue() {
        val sheet = DownloadQueueSheet()
        sheet.onRetry = { record ->
            com.andrometa.pullout.download.RetryDownloadWorker
                .enqueue(this, record.id, record.originalUrl, record.filename)
        }
        sheet.show(supportFragmentManager, DownloadQueueSheet.TAG)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                submitUrl(text)
            }
            intent.action == ACTION_SHORTCUT_PASTE -> {
                checkClipboard(autoSubmit = true)
            }
            intent.action == ACTION_SHORTCUT_QUEUE -> {
                openQueue()
            }
        }
    }

    private fun checkClipboard(autoSubmit: Boolean = false) {
        val url = ClipboardHelper.getSupportedUrl(this) ?: return
        if (autoSubmit) {
            submitUrl(url)
            return
        }
        Snackbar.make(binding.root, R.string.clip_prompt, Snackbar.LENGTH_LONG)
            .setAction(R.string.clip_action) { submitUrl(url) }
            .setActionTextColor(color(R.color.neon_cyan))
            .show()
    }

    private fun submitUrl(rawText: String?) {
        val url = UrlMatcher.extractUrl(rawText)
        if (url == null) {
            showErrorBanner(getString(R.string.err_invalid_url))
            return
        }
        if (!NodeServerManager.isReady()) {
            Snackbar.make(binding.root, R.string.status_init, Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.tvStatus.text = getString(R.string.status_working)
        binding.tvStatus.setTextColor(color(R.color.neon_cyan))
        NodeServerManager.currentOriginalUrl = url

        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    CobaltApiClient.submit(
                        settings.cobaltInstanceUrl, url,
                        settings.audioOnlyMode, settings.defaultQuality
                    )
                }.getOrElse { CobaltResponse.CobaltError("api.unreachable", null) }
            }
            routeResponse(url, response)
            // Restore ready status text.
            if (NodeServerManager.isReady()) {
                binding.tvStatus.text = getString(R.string.status_ready)
                binding.tvStatus.setTextColor(color(R.color.neon_green))
            }
        }
    }

    private suspend fun routeResponse(originalUrl: String, response: CobaltResponse) {
        when (response) {
            is CobaltResponse.Tunnel ->
                DownloadService.startDirect(
                    this, response.url, response.filename, response.mimeType, originalUrl
                )
            is CobaltResponse.Redirect ->
                DownloadService.startDirect(
                    this, response.url, response.filename, response.mimeType, originalUrl
                )
            is CobaltResponse.LocalProcessing ->
                DownloadService.startMux(this, repository, response, originalUrl)
            is CobaltResponse.Picker -> {
                val sheet = PickerSheet.newInstance(response.items)
                sheet.onItemsSelected = { items ->
                    items.forEach { item ->
                        DownloadService.startDirect(
                            this, item.url,
                            "pullout_${System.currentTimeMillis()}.${extFor(item.type)}",
                            mimeFor(item.type), originalUrl
                        )
                    }
                }
                sheet.show(supportFragmentManager, PickerSheet.TAG)
            }
            is CobaltResponse.CobaltError ->
                showErrorBanner(translateError(response.code))
        }
    }

    private fun extFor(type: String): String = when (type) {
        "video" -> "mp4"; "audio" -> "mp3"; "gif" -> "gif"; else -> "jpg"
    }

    private fun mimeFor(type: String): String = when (type) {
        "video" -> "video/mp4"; "audio" -> "audio/mpeg"; "gif" -> "image/gif"; else -> "image/jpeg"
    }

    private fun translateError(code: String): String = when {
        code.contains("unreachable") -> getString(R.string.err_api_unreachable)
        code.contains("link.invalid") || code.contains("link_invalid") -> getString(R.string.err_link_invalid)
        code.contains("unsupported") || code.contains("service") -> getString(R.string.err_link_unsupported)
        code.contains("unavailable") -> getString(R.string.err_content_unavailable)
        code.contains("too_long") || code.contains("too.long") -> getString(R.string.err_content_too_long)
        code.contains("rate") -> getString(R.string.err_rate_limit)
        else -> getString(R.string.err_generic)
    }

    private fun showErrorBanner(message: String) {
        binding.viewError.visibility = View.VISIBLE
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.text = message
        binding.root.postDelayed({
            binding.viewError.visibility = View.GONE
            binding.tvError.visibility = View.GONE
        }, 5000)
    }

    private fun handleFirstLaunch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (!settings.firstLaunchDone) {
            settings.firstLaunchDone = true
            Snackbar.make(binding.root, R.string.settings_battery, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_open) {
                    SettingsSheet().show(supportFragmentManager, SettingsSheet.TAG)
                }.show()
        }
    }

    private fun startPulse(view: View) {
        val anim = AlphaAnimation(0.35f, 1.0f).apply {
            duration = 700
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        view.startAnimation(anim)
    }

    private fun stopPulse(view: View) {
        view.clearAnimation()
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    override fun onDestroy() {
        super.onDestroy()
        // No WebView to destroy; lifecycleScope is cancelled automatically.
    }

    companion object {
        const val ACTION_SHORTCUT_PASTE = "com.andrometa.pullout.SHORTCUT_PASTE"
        const val ACTION_SHORTCUT_QUEUE = "com.andrometa.pullout.SHORTCUT_QUEUE"
    }
}
```

> The import `com.andrometa.pullout.util.RetryBridge` in the file above is not used; remove it. (Listed here so the agent deletes the stray import — the retry path uses `RetryDownloadWorker.enqueue` directly in `openQueue`.)

- [ ] Step 3: Remove the unused import. Edit `MainActivity.kt` to delete the line:
```kotlin
import com.andrometa.pullout.util.RetryBridge
```

- [ ] Step 4: Finalize `app\src\main\res\xml\shortcuts.xml`.
```xml
<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <shortcut
        android:shortcutId="paste_download"
        android:enabled="true"
        android:icon="@drawable/ic_queue"
        android:shortcutShortLabel="@string/shortcut_paste_short"
        android:shortcutLongLabel="@string/shortcut_paste_long">
        <intent
            android:action="com.andrometa.pullout.SHORTCUT_PASTE"
            android:targetPackage="com.andrometa.pullout"
            android:targetClass="com.andrometa.pullout.MainActivity"/>
    </shortcut>
    <shortcut
        android:shortcutId="open_queue"
        android:enabled="true"
        android:icon="@drawable/ic_queue"
        android:shortcutShortLabel="@string/shortcut_queue_short"
        android:shortcutLongLabel="@string/shortcut_queue_long">
        <intent
            android:action="com.andrometa.pullout.SHORTCUT_QUEUE"
            android:targetPackage="com.andrometa.pullout"
            android:targetClass="com.andrometa.pullout.MainActivity"/>
    </shortcut>
</shortcuts>
```

- [ ] Step 5: Full build.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat assembleDebug
```
Expected: `BUILD SUCCESSFUL`. APK at `app\build\outputs\apk\debug\app-debug.apk`.

- [ ] Step 6: Commit.
```bash
git add -A && git commit -m "feat: PulloutApplication + MainActivity + shortcuts (full app wired)"
```

---

### Task 13: Build debug APK + GitHub release

**Files:** None new; produces `app\build\outputs\apk\debug\app-debug.apk`

- [ ] Step 1: Clean build.
```powershell
cd D:\Projects\PULLOUT
.\gradlew.bat clean assembleDebug
```
Expected: `BUILD SUCCESSFUL`. APK exists.

- [ ] Step 2: Verify package, permissions, launcher activity with aapt.
```powershell
cd D:\Projects\PULLOUT
$aapt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Recurse -Filter aapt2.exe | Select-Object -First 1
# Prefer aapt (badging) if present:
$aaptBadge = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Recurse -Filter aapt.exe | Select-Object -First 1
& $aaptBadge.FullName dump badging app\build\outputs\apk\debug\app-debug.apk | Select-String -Pattern "package:|launchable-activity:|uses-permission:"
```
Expected: shows `package: name='com.andrometa.pullout.debug'` (debug suffix), `launchable-activity: name='com.andrometa.pullout.MainActivity'`, and the 7 declared permissions including `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`.

- [ ] Step 3: Report APK size.
```powershell
cd D:\Projects\PULLOUT
$apk = Get-Item app\build\outputs\apk\debug\app-debug.apk
"{0:N1} MB" -f ($apk.Length / 1MB)
```
Expected: a size (likely 60-120 MB due to bundled node + ffmpeg native libs). Record it.

- [ ] Step 4: Create the GitHub repo and push all commits.
```powershell
cd D:\Projects\PULLOUT
gh repo create Andro-Meta/pullout-android --public --source=. --remote=origin --push
```
Expected: repo created, `main` pushed. If `main` does not exist yet:
```powershell
git branch -M main
git push -u origin main
```

- [ ] Step 5: Tag and create the release with the APK attached.
```powershell
cd D:\Projects\PULLOUT
git tag v1.0-debug
git push origin v1.0-debug
gh release create v1.0-debug app\build\outputs\apk\debug\app-debug.apk `
  --repo Andro-Meta/pullout-android `
  --title "PULLOUT v1.0 (debug)" `
  --notes "Self-contained Android media downloader. On-device patched cobalt API via nodejs-mobile on localhost:9000; ffmpeg-kit muxing; native cyber-retro UI."
```
Expected: `https://github.com/Andro-Meta/pullout-android/releases/tag/v1.0-debug` with `app-debug.apk` attached.

- [ ] Step 6: Final commit (CI metadata / README if any was added). If the working tree is clean, skip.
```bash
git add -A && git commit -m "chore: v1.0-debug release artifacts" || echo "nothing to commit"
```

---

## Post-implementation verification checklist

- [ ] `app\src\main\assets\nodejs-project\main.js` and `pullout-version.txt` exist (Task 1).
- [ ] `app\libs\nodejs-mobile-android.aar` present and its node startup class name matches the import in `NodeServerManager.kt` (Task 6).
- [ ] `gradlew assembleDebug` is green from a clean tree (Task 13).
- [ ] Unit tests pass: `gradlew testDebugUnitTest` (DownloadStatusTest 5, UrlMatcherTest 24, CobaltResponseTest 4 = 33 tests).
- [ ] Manifest declares `foregroundServiceType="dataSync"` on both services and code passes `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (Tasks 2, 6, 8).
- [ ] `startForeground()` is the first call in both services' `onStartCommand` before any coroutine (Tasks 6, 8).
- [ ] `SettingsSheet.onStop` uses `_binding?.etCobaltUrl` (Task 11).
- [ ] `NotificationHelper.showComplete` accepts `mimeType` and uses it for the VIEW intent (Task 4).
- [ ] `LocalMuxingManager` deletes temp files in the outer `finally` (Task 7).
- [ ] `DownloadRecord` has `mediaStoreUriString`, `isMuxed`, `muxPhase`; saved after MediaStore finalize (Tasks 3, 7, 8).
- [ ] On-device smoke test (manual, via Verdict/adb): launch app → status reaches "▓ READY" → paste a YouTube URL → file appears in Download/PULLOUT.
