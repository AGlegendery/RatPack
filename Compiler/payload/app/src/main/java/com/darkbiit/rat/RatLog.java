package com.darkbiit.rat;

import android.util.Log;

/** Thin logging helper so every message carries the configured tag. */
public final class RatLog {

    private RatLog() {
    }

    public static void i(String msg) {
        Log.i(RatCfg.TAG, msg);
    }

    public static void w(String msg) {
        Log.w(RatCfg.TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(RatCfg.TAG, msg, t);
    }
}
