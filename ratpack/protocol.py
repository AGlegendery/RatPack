"""
protocol.py — wire format shared with the Android payload.

Framing (all integers big-endian):

    +--------+--------+-----------------+------------------+
    | ver(1) | type(1)| length (4 bytes) | payload (N bytes)|
    +--------+--------+-----------------+------------------+

    ver    = 0x01
    type   = RatProto.TYPE_*
    length = payload length in bytes (max 16 MiB)

Payload of every type except PING/PONG is UTF-8 JSON.

This module is the Python mirror of rat/src/main/java/com/darkbiit/rat/RatProto.java.
The two must stay byte-identical: the header is 6 bytes (ver + type + 4-byte
length), NOT 5.
"""

import struct

VERSION = 0x01

TYPE_REGISTER = 0x01
TYPE_REGISTER_ACK = 0x02
TYPE_COMMAND = 0x10
TYPE_RESPONSE = 0x11
TYPE_EVENT = 0x12
TYPE_PING = 0x20
TYPE_PONG = 0x21

MAX_PAYLOAD = 16 * 1024 * 1024

HEADER_LEN = 6
HEADER = struct.Struct("!BBI")


def pack(type_: int, payload: bytes = b"") -> bytes:
    if len(payload) > MAX_PAYLOAD:
        raise ValueError("payload too large")
    return struct.pack("!BBI", VERSION, type_, len(payload)) + payload


def unpack_header(head: bytes):
    if len(head) != HEADER_LEN:
        raise ValueError(f"short header: {len(head)} != {HEADER_LEN}")
    ver, type_, length = struct.unpack("!BBI", head)
    if ver != VERSION:
        raise ValueError(f"bad protocol version: {ver}")
    if length > MAX_PAYLOAD:
        raise ValueError(f"frame too large: {length}")
    return type_, length


def recv_exactly(sock, n: int) -> bytes:
    """Reads exactly n bytes; returns None on clean EOF before any byte."""
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            if not buf:
                return None
            raise ConnectionError("truncated frame")
        buf += chunk
    return bytes(buf)


def recv_frame(sock):
    """Returns (type, payload) or None on clean EOF."""
    head = recv_exactly(sock, HEADER_LEN)
    if head is None:
        return None
    type_, length = unpack_header(head)
    payload = recv_exactly(sock, length) if length else b""
    if payload is None:
        raise ConnectionError("truncated payload")
    return type_, payload
