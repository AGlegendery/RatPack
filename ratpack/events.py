#!/usr/bin/env python3
"""Prints the bot's event log (responses + forwarded events)."""
import json
import socket
import sys


def call(req, timeout=20):
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
    sys.exit(1)
bid = real[0]["id"]
print("bot:", bid)
ev = call({"op": "bot_events", "bot": bid}, timeout=15).get("events", [])
print("events:", len(ev))
kinds = {}
for e in ev:
    d = e.get("data", {})
    k = d.get("kind", "?")
    kinds[k] = kinds.get(k, 0) + 1
print("kinds:", kinds)
print("--- last 8 ---")
for e in ev[-8:]:
    print(json.dumps(e, ensure_ascii=False)[:400])
