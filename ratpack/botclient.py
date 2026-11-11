"""
botclient.py — client for the C2 control channel.

Line-delimited JSON over TCP:

  -> {"op":"list"}
  <- {"ok":true,"bots":[...]}
"""

import json
import socket


class ControlClient:
    def __init__(self, host="127.0.0.1", port=8778, timeout=30):
        self.host = host
        self.port = port
        self.timeout = timeout

    def call(self, req: dict, timeout: float = None) -> dict:
        sock = socket.create_connection((self.host, self.port), timeout=timeout or self.timeout)
        try:
            sock.settimeout(None)
            sock.sendall(json.dumps(req).encode("utf-8") + b"\n")
            buf = b""
            while b"\n" not in buf:
                chunk = sock.recv(65536)
                if not chunk:
                    raise ConnectionError("control channel closed")
                buf += chunk
            line = buf.split(b"\n", 1)[0]
            return json.loads(line.decode("utf-8"))
        finally:
            sock.close()
