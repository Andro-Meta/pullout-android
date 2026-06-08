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

# 6. Build a self-contained deploy directory.
#    NOTE: `pnpm deploy` prunes too aggressively here and drops transitive deps
#    of youtubei.js (e.g. @bufbuild/protobuf), so we copy the api package and run
#    a plain production npm install instead — this resolves the full dependency
#    tree (~90 packages vs ~20 from pnpm deploy).
if (Test-Path $DeployDir) { Remove-Item -Recurse -Force $DeployDir }
New-Item -ItemType Directory -Force -Path $DeployDir | Out-Null
robocopy (Join-Path $SrcDir "api") $DeployDir /E /NFL /NDL /NJH /NJS /NC /NS /NP | Out-Null

# 6a. Pre-stage the workspace-local @imput/version-info package so npm can resolve it.
$VersionInfoDest = Join-Path $DeployDir "node_modules\@imput\version-info"
New-Item -ItemType Directory -Force -Path $VersionInfoDest | Out-Null
robocopy (Join-Path $SrcDir "packages\version-info") $VersionInfoDest /E /NFL /NDL /NJH /NJS /NC /NS /NP | Out-Null

# 6b. Rewrite the workspace: protocol ref so npm install accepts it.
$DeployPkgPath = Join-Path $DeployDir "package.json"
$pkgText = Get-Content $DeployPkgPath -Raw
$pkgText = $pkgText -replace '"@imput/version-info":\s*"workspace:\^"', '"@imput/version-info": "*"'
Set-Content -Path $DeployPkgPath -Value $pkgText -NoNewline

# 6c. Full production install (resolves all transitive deps).
Push-Location $DeployDir
npm install --production --legacy-peer-deps
Pop-Location

# 7. Ensure main.js is at deploy root
$DeployMain = Join-Path $DeployDir "main.js"
if (-not (Test-Path $DeployMain)) {
    Copy-Item -Force $MainJs $DeployMain
}

# 8. Write version marker (used by NodeServerManager to decide re-extraction)
Set-Content -Path (Join-Path $DeployDir "pullout-version.txt") -Value $GitHash -NoNewline

# 9. Apply Android-specific runtime patches (ICU regexes, ffmpeg-static,
#    version-info git shim). See scripts/patch-cobalt.py for details.
python (Join-Path $PSScriptRoot "patch-cobalt.py") $DeployDir
if ($LASTEXITCODE -ne 0) { throw "patch-cobalt.py failed" }

# 10. Copy to app assets
if (Test-Path $AssetsDir) { Remove-Item -Recurse -Force $AssetsDir }
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null
Get-ChildItem -Path $DeployDir | Copy-Item -Destination $AssetsDir -Recurse -Force

Write-Host "==> nodejs-project ready at $AssetsDir (cobalt $GitHash) SUCCESS"
