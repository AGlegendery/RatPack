package com.darkbiit.rat;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wire protocol for the RAT channel.
 *
 * Framing (all integers big-endian):
 *
 *   +--------+--------+-----------------+------------------+
 *   | ver(1) | type(1)| length (4 bytes) | payload (N bytes)|
 *   +--------+--------+-----------------+------------------+
 *
 *   ver    = 0x01
 *   type   = RatProto.TYPE_*
 *   length = payload length in bytes (max 16 MiB)
 *
 * Payload of every type except TYPE_PING/TYPE_PONG is UTF-8 JSON.
 *
 * The server implements the same header in server/protocol.py; the two sides
 * must stay byte-identical.
 */
public final class RatProto {

    public static final byte VERSION = 0x01;

    public static final byte TYPE_REGISTER = 0x01;    // bot -> server
    public static final byte TYPE_REGISTER_ACK = 0x02; // server -> bot
    public static final byte TYPE_COMMAND = 0x10;     // server -> bot
    public static final byte TYPE_RESPONSE = 0x11;    // bot -> server
    public static final byte TYPE_EVENT = 0x12;       // bot -> server
    public static final byte TYPE_PING = 0x20;        // bot -> server
    public static final byte TYPE_PONG = 0x21;        // server -> bot

    /** Hard cap on a single frame payload. */
    public static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private RatProto() {
    }

    /** Reads exactly one frame. */
    public static Frame read(InputStream in) throws IOException {
        byte[] head = new byte[6];
        readFully(in, head, 6);
        byte ver = head[0];
        if (ver != VERSION) {
            throw new IOException("bad protocol version: 0x" + Integer.toHexString(ver & 0xff));
        }
        byte type = head[1];
        long len = ((long) (head[2] & 0xff) << 24)
                | ((long) (head[3] & 0xff) << 16)
                | ((long) (head[4] & 0xff) << 8)
                | (long) (head[5] & 0xff);
        if (len < 0 || len > MAX_PAYLOAD) {
            throw new IOException("frame too large: " + len);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload, payload.length);
        return new Frame(type, payload);
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("unexpected EOF");
            }
            off += r;
        }
    }

    /** Writes one frame. */
    public static void write(OutputStream out, byte type, byte[] payload) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeByte(VERSION);
        dos.writeByte(type);
        dos.writeInt(payload == null ? 0 : payload.length);
        if (payload != null && payload.length > 0) {
            dos.write(payload);
        }
        dos.flush();
    }

    /** A decoded frame: type plus payload. */
    public static final class Frame {
        public final byte type;
        public final byte[] payload;

        public Frame(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
