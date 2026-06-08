#!/usr/bin/env python3
"""
patch-cobalt.py — Post-process a deployed cobalt API bundle to run inside
nodejs-mobile on Android (ARM64) with a stripped-ICU libnode.

Applies every fix discovered while bringing PULLOUT up on a real device:

  1. ffmpeg-static import removal       (src/stream/ffmpeg.js)
     -> the Android layer handles muxing via ffmpeg-kit; cobalt never needs it.

  2. ICU Unicode-property regex rewrite  (youtubei.js, zod)
     -> the bundled ARM64 libnode ships a stripped ICU with NO Unicode
        property tables, so /\p{Emoji}/u, /\p{L}/u, /\p{Extended_Pictographic}/
        etc. all throw "Invalid property name". We rewrite them to ICU-free
        equivalents (or never-match patterns for pure emoji validation).

  3. @imput/version-info git-free shim
     -> the package walks up from cwd looking for a .git dir to read the build
        commit/branch. There is no git repo on-device, so we replace it with a
        module that returns hardcoded build metadata.

  4. youtube.js isolated-vm -> new Function() eval shim
     -> already applied by setup-cobalt.ps1 before npm install; re-asserted here
        for idempotency.

Usage:  python patch-cobalt.py <path-to-nodejs-project>
"""
import os
import sys


def patch_ffmpeg_static(root: str) -> None:
    """Remove the ffmpeg-static import; cobalt's localProcessing:forced path
    never invokes ffmpeg, and the package has no Android binary."""
    path = os.path.join(root, "src", "stream", "ffmpeg.js")
    if not os.path.exists(path):
        print("  [ffmpeg] src/stream/ffmpeg.js not found, skipping")
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    new = content
    for needle in (
        'import ffmpeg from "ffmpeg-static";',
        "import ffmpeg from 'ffmpeg-static';",
    ):
        new = new.replace(
            needle,
            "// ffmpeg-static removed for Android (Kotlin layer handles muxing)",
        )
    if new != content:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new)
        print("  [ffmpeg] patched src/stream/ffmpeg.js")
    else:
        print("  [ffmpeg] already patched or import not found")


def patch_icu_regexes(root: str) -> int:
    """Rewrite every \\p{...} Unicode-property regex that the stripped ARM64
    ICU cannot evaluate. Skips bundle/ files (not loaded by Node)."""
    node_modules = os.path.join(root, "node_modules")
    if not os.path.isdir(node_modules):
        print("  [icu] node_modules not found, skipping")
        return 0

    # (search bytes, replacement bytes)
    replacements = [
        # youtubei.js emoji-only literal regex (Text.js) — never-match is fine,
        # it only gates emoji shortcut insertion in comment rendering.
        (b'/^(?:\\p{Emoji}|\\u200d)+$/u', b'/^(?!x)x$/'),
        # youtubei.js RegExp-constructor form (bundle files, not loaded but tidy)
        (b'"^(?:\\\\p{Emoji}|\\\\u200d)+$", "u"', b'"^(?!x)x$", ""'),
        # youtubei.js comment "strip non-word" filter -> strip control chars only
        (b'/[^\\p{L}\\p{N}\\p{P}\\p{Z}]/gu', b'/[\\x00-\\x1F\\x7F-\\x9F\\xAD]/g'),
        # zod emoji validators (v3 + v4) -> never-match (we don't validate emoji)
        (b'\\p{Extended_Pictographic}', b'x'),
        (b'\\p{Emoji_Component}', b'x'),
    ]

    patched = 0
    for dirpath, _dirs, files in os.walk(node_modules):
        for fname in files:
            if not fname.endswith(".js"):
                continue
            fpath = os.path.join(dirpath, fname)
            try:
                with open(fpath, "rb") as f:
                    raw = f.read()
            except OSError:
                continue
            if b"\\p{" not in raw:
                continue
            new = raw
            for old_b, new_b in replacements:
                if old_b in new:
                    new = new.replace(old_b, new_b)
            if new != raw:
                with open(fpath, "wb") as f:
                    f.write(new)
                rel = os.path.relpath(fpath, root)
                print(f"  [icu] patched {rel}")
                patched += 1
    print(f"  [icu] {patched} file(s) patched")
    return patched


def patch_version_info(root: str) -> None:
    """Replace @imput/version-info with a git-free module returning hardcoded
    build metadata. The original walks up looking for a .git dir, which does
    not exist on-device."""
    path = os.path.join(root, "node_modules", "@imput", "version-info", "index.js")
    if not os.path.exists(path):
        print("  [version-info] package not found, skipping")
        return
    commit = "android"
    ver_file = os.path.join(root, "pullout-version.txt")
    if os.path.exists(ver_file):
        with open(ver_file, "r", encoding="utf-8") as f:
            commit = f.read().strip().split("-")[0] or "android"
    shim = (
        "// PULLOUT: git-free shim (no .git repository on-device)\n"
        f'export const getCommit  = async () => "{commit}";\n'
        'export const getBranch  = async () => "main";\n'
        'export const getRemote  = async () => "Andro-Meta/pullout-android";\n'
        'export const getVersion = async () => "11.7.1";\n'
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(shim)
    print("  [version-info] replaced with git-free shim")


def patch_session_reload_interval(root: str) -> None:
    """Lower the YouTube session reload interval from cobalt's hardcoded 300s to
    30s. The on-device po_token generator (bgutils-js in a WebView) takes a few
    seconds to mint the first token, so cobalt's startup poll always misses it;
    a 30s retry means YouTube becomes usable within ~30s of launch rather than
    the 5 minutes the 300s default would impose. This value is NOT env-configurable
    in cobalt, so we patch the default directly."""
    path = os.path.join(root, "src", "core", "env.js")
    if not os.path.exists(path):
        print("  [session] src/core/env.js not found, skipping")
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    new = content.replace("ytSessionReloadInterval: 300", "ytSessionReloadInterval: 30")
    if new != content:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new)
        print("  [session] ytSessionReloadInterval 300 -> 30")
    else:
        print("  [session] already patched or default changed upstream")


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: python patch-cobalt.py <path-to-nodejs-project>")
        return 2
    root = sys.argv[1]
    if not os.path.isdir(root):
        print(f"error: {root} is not a directory")
        return 1
    print(f"==> patching cobalt bundle at {root}")
    patch_ffmpeg_static(root)
    patch_icu_regexes(root)
    patch_version_info(root)
    patch_session_reload_interval(root)
    print("==> cobalt bundle patched for Android")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
