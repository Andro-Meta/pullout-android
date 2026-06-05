# scripts/setup-cobalt.ps1
$ErrorActionPreference = "Stop"
$Root      = Split-Path -Parent $PSScriptRoot
$SrcDir    = Join-Path $Root "cobalt-src"
$DeployDir = Join-Path $Root "cobalt-deploy"
$AssetsDir = Join-Path $Root "app\src\main\assets\nodejs-project"

Write-Host "==> PULLOUT cobalt setup"

# 1. Ensure pnpm
if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) {
    Write-Host "Installing pnpm..."
    npm install -g pnpm
}

# 2. Clone cobalt (shallow)
if (Test-Path $SrcDir) { Remove-Item -Recurse -Force $SrcDir }
git clone --depth=1 https://github.com/imputnet/cobalt.git $SrcDir
$GitHash = (git -C $SrcDir rev-parse --short HEAD).Trim()
Write-Host "cobalt @ $GitHash"

# 3. Patch youtube.js: remove isolated-vm
$YtPath = Join-Path $SrcDir "api\src\processing\services\youtube.js"
if (Test-Path $YtPath) {
    $yt = Get-Content $YtPath -Raw
    # Remove isolated-vm import line
    $yt = ($yt -split "`r?`n" | Where-Object { $_ -notmatch "isolated-vm" }) -join "`n"
    # Replace Platform.shim.eval with new Function() evaluator
    # Prepend a shim that cobalt's youtubei.js will pick up via Platform.shim.eval
    $shim = @'
// PULLOUT: android-safe eval shim (replaces isolated-vm)
if (typeof globalThis.__pulloutEvalInstalled === 'undefined') {
  globalThis.__pulloutEvalInstalled = true;
  const _origPlatformShimEval = (code) =>
    (new Function('"use strict"; return (' + code + ')'))();
  // youtubei.js reads Platform.shim.eval; we override after import
  Promise.resolve().then(async () => {
    try {
      const { Platform } = await import('youtubei.js');
      if (Platform && Platform.shim) Platform.shim.eval = async (data) => _origPlatformShimEval(data.output || data);
    } catch(e) { /* non-fatal */ }
  });
}
'@
    $yt = $shim + "`n" + $yt
    Set-Content -Path $YtPath -Value $yt -NoNewline
    Write-Host "patched youtube.js"
} else {
    Write-Warning "youtube.js not found at $YtPath - check cobalt repo structure"
}

# 4. Patch api/package.json: remove native deps
$PkgPath = Join-Path $SrcDir "api\package.json"
$pkg = Get-Content $PkgPath -Raw | ConvertFrom-Json
foreach ($dep in @("isolated-vm","ffmpeg-static","freebind")) {
    if ($pkg.dependencies.PSObject.Properties.Name -contains $dep) {
        $pkg.dependencies.PSObject.Properties.Remove($dep)
        Write-Host "removed dep: $dep"
    }
    if ($pkg.devDependencies -and $pkg.devDependencies.PSObject.Properties.Name -contains $dep) {
        $pkg.devDependencies.PSObject.Properties.Remove($dep)
    }
}
($pkg | ConvertTo-Json -Depth 50) | Set-Content -Path $PkgPath -NoNewline

# 5. Write main.js entry point
$MainJs = Join-Path $SrcDir "api\main.js"
@'
// PULLOUT nodejs-mobile entry point
process.env.API_URL            = process.env.API_URL            || "http://localhost:9000/";
process.env.API_PORT           = process.env.API_PORT           || "9000";
process.env.API_LISTEN_ADDRESS = process.env.API_LISTEN_ADDRESS || "127.0.0.1";
// localProcessing:forced is sent per-request by the Android client so cobalt never
// needs ffmpeg on this side; only extraction + stream URL resolution happens here.
import("./src/cobalt.js").catch((e) => {
    console.error("[PULLOUT] cobalt boot failed:", e.message || e);
    process.exit(1);
});
'@ | Set-Content -Path $MainJs -NoNewline
Write-Host "wrote main.js"

# 6. Install workspace deps
Push-Location $SrcDir
pnpm install --no-frozen-lockfile
Pop-Location

# 7. Detect api package name
$ApiPkg = (Get-Content $PkgPath -Raw | ConvertFrom-Json).name
Write-Host "api package: $ApiPkg"

# 8. pnpm deploy → self-contained directory
if (Test-Path $DeployDir) { Remove-Item -Recurse -Force $DeployDir }
Push-Location $SrcDir
pnpm --filter "$ApiPkg" deploy --prod $DeployDir
Pop-Location

# 9. Ensure main.js is at deploy root
$DeployMain = Join-Path $DeployDir "main.js"
if (-not (Test-Path $DeployMain)) {
    Copy-Item -Force $MainJs $DeployMain
}

# 10. Write version
Set-Content -Path (Join-Path $DeployDir "pullout-version.txt") -Value $GitHash -NoNewline

# 11. Copy to app assets
if (Test-Path $AssetsDir) { Remove-Item -Recurse -Force $AssetsDir }
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null
Get-ChildItem -Path $DeployDir | Copy-Item -Destination $AssetsDir -Recurse -Force

Write-Host "==> nodejs-project ready at $AssetsDir (cobalt $GitHash) SUCCESS"
