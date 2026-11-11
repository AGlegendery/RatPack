"""
httpbot.py — HTTP long-polling transport for the bot channel.

Why this exists
---------------
On shared hosting (cPanel/Plesk, Heroku-style dynos, Cloudflare-fronted
boxes) you usually cannot bind a raw TCP port and have the outside world
reach it. You only get HTTP(S) on a port the platform assigns.

This module lets a bot speak the same command/response protocol over plain
HTTP:

    POST /bot/poll    the bot asks for work; the response is either a
                      command frame or an empty 204 after the poll timeout
    POST /bot/send    the bot sends a frame (register, response, event)

The server holds the bot's commands in a queue and the pending response in
the Bot object, exactly like the TCP path, so the panel and the rest of the
code do not change at all.

The transport costs one long-poll request per bot per interval instead of
one persistent socket, which is the trade-off shared hosting forces.
"""

import json
import time

from . import protocol as P


class HttpBotRegistry:
    """
    Registry for bots that speak HTTP.

    It reuses the same Bot objects as the TCP path, so the panel sees one
    uniform bot list regardless of transport.
    """

    def __init__(self, server):
        self.server = server
        self.by_token = {}       # token -> Bot
        self.lock = __import__("threading").Lock()

    # ------------------------------------------------------------------
    # bot-facing endpoints
    # ------------------------------------------------------------------

    def register(self, info: dict):
        """Handles a registration frame; returns (bot, error)."""
        secret = self.server.secret
        if secret and info.get("secret") != secret:
            return None, "bad secret"

        bot_id = info.get("id") or __import__("uuid").uuid4().hex[:12]
        info["id"] = bot_id

        bot = self.server.registry.get(bot_id)
        if bot is None:
            bot = self.server.make_http_bot(bot_id, info)
            with self.lock:
                self.by_token[bot_id] = bot
            self.server.registry.add(bot)
        else:
            # Re-registration from the same bot: refresh the info.
            bot.info.update(info)
            bot.last_seen = time.time()
            bot.last_pong = time.time()
            bot.alive = True
        return bot, None

    def get(self, bot_id: str):
        with self.lock:
            return self.by_token.get(bot_id)

    def drop(self, bot_id: str):
        with self.lock:
            bot = self.by_token.pop(bot_id, None)
        if bot:
            bot.close()
            self.server.registry.remove(bot)

    # ------------------------------------------------------------------
    # frame helpers
    # ------------------------------------------------------------------

    @staticmethod
    def frame_to_json(type_: int, payload: bytes):
        return {
            "t": type_,
            "b": payload.hex(),
        }

    @staticmethod
    def json_to_frame(obj: dict):
        type_ = int(obj.get("t", 0))
        hexdata = obj.get("b", "")
        payload = bytes.fromhex(hexdata) if hexdata else b""
        return type_, payload

    # ------------------------------------------------------------------
    # the two bot endpoints
    # ------------------------------------------------------------------

    def poll(self, bot_id: str, timeout: float = 25.0):
        """
        Waits for a command for the bot and returns it.

        Returns a dict {"t": type, "b": hex} or None when the poll timed out.
        """
        bot = self.get(bot_id)
        if bot is None:
            return None

        deadline = time.time() + timeout
        with bot.cmd_cv:
            while bot.alive:
                # A command may already be queued.
                try:
                    type_, payload = bot.outbox.get_nowait()
                    return self.frame_to_json(type_, payload)
                except Exception:
                    pass
                remaining = deadline - time.time()
                if remaining <= 0:
                    return None
                bot.cmd_cv.wait(min(remaining, 1.0))
        return None

    def send(self, bot_id: str, type_: int, payload: bytes):
        """Delivers a frame from the bot to the server."""
        bot = self.get(bot_id)
        if bot is None:
            return False
        bot.last_seen = time.time()
        bot.last_pong = time.time()

        if type_ == P.TYPE_RESPONSE:
            try:
                obj = json.loads(payload.decode("utf-8"))
            except Exception:
                return True
            bot.deliver_response(obj.get("id", ""), obj)
            bot.record_event({"kind": "response", "data": obj})
            self.server.registry.events.put(
                {"type": "response", "bot": bot.id, "data": obj})
        elif type_ == P.TYPE_EVENT:
            try:
                obj = json.loads(payload.decode("utf-8"))
            except Exception:
                return True
            bot.record_event({"kind": "event", "data": obj})
            self.server.registry.events.put(
                {"type": "event", "bot": bot.id, "data": obj})
        elif type_ == P.TYPE_PING:
            bot.send(P.TYPE_PONG, payload)
        elif type_ == P.TYPE_PONG:
            pass
        return True
