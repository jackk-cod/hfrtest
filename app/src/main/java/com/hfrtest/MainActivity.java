package com.hfrtest;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Aplikasi UJI v2: cek dukungan high-speed, rekam 6 detik di 120 atau 240 FPS,
 * dengan opsi AE+AWB lock (dikunci setelah 1,5 detik), lalu laporkan hasilnya.
 */
public class MainActivity extends Activity {

    private static final int REC_SECONDS = 6;
    private static final long LOCK_DELAY_MS = 1500;

    private CameraManager manager;
    private HandlerThread camThread;
    private Handler camHandler;

    private SurfaceView surfaceView;
    private TextView logView;
    private ScrollView scroll;
    private Button btnInfo, btn120, btn120Lock, btn240, btn240Lock;

    private String camId;
    private CameraCharacteristics chars;
    private Size recSize;
    private Range<Integer> recRange;
    private int targetFps = 120;
    private boolean aeLockUsed = false;
    private int lockFrameIndex = -1;

    private CameraDevice cameraDevice;
    private CameraConstrainedHighSpeedCaptureSession hsSession;
    private MediaRecorder recorder;
    private Uri videoUri;
    private ParcelFileDescriptor videoPfd;
    private Surface prevSurface, recSurface;
    private CameraCaptureSession.CaptureCallback captureCb;
    private volatile boolean recording = false;

    // tiap elemen: {sensorTimestampNs, exposureNs, iso}
    private final List<long[]> frames = Collections.synchronizedList(new ArrayList<long[]>());

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        camThread = new HandlerThread("cam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        manager = (CameraManager) getSystemService(CAMERA_SERVICE);

        captureCb = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
                Long ex = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
                if (ts != null) {
                    frames.add(new long[]{ts, ex == null ? -1 : ex, iso == null ? -1 : iso});
                }
            }
        };

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        } else {
            init();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            init();
        } else {
            log("Izin kamera ditolak. Buka Pengaturan > Aplikasi > HFR Test > Izin, aktifkan Kamera.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanup();
        if (camThread != null) camThread.quitSafely();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(16, 16, 16, 16);
        root.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        btnInfo = makeButton("1. Cek kemampuan kamera");
        btn120 = makeButton("2. Rekam 120 FPS - AUTO");
        btn120Lock = makeButton("3. Rekam 120 FPS - AE LOCK");
        btn240 = makeButton("4. Rekam 240 FPS - AUTO");
        btn240Lock = makeButton("5. Rekam 240 FPS - AE LOCK");
        right.addView(btnInfo);
        right.addView(btn120);
        right.addView(btn120Lock);
        right.addView(btn240);
        right.addView(btn240Lock);

        btnInfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                camHandler.post(new Runnable() {
                    @Override public void run() { dumpInfo(); }
                });
            }
        });
        btn120.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRecording(120, false); }
        });
        btn120Lock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRecording(120, true); }
        });
        btn240.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRecording(240, false); }
        });
        btn240Lock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRecording(240, true); }
        });

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        right.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private void setButtons(final boolean enabled) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                btnInfo.setEnabled(enabled);
                btn120.setEnabled(enabled);
                btn120Lock.setEnabled(enabled);
                btn240.setEnabled(enabled);
                btn240Lock.setEnabled(enabled);
            }
        });
    }

    private void log(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                logView.append(s + "\n");
                scroll.post(new Runnable() {
                    @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    // ------------------------------------------------------- info & seleksi

    private void init() {
        camHandler.post(new Runnable() {
            @Override public void run() {
                log("HFR Test v2");
                dumpInfo();
                selectFor(120);
            }
        });
    }

    private static String yn(boolean b) { return b ? "YA" : "TIDAK"; }

    private static String levelName(Integer lv) {
        if (lv == null) return "?";
        switch (lv) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return String.valueOf(lv);
        }
    }

    private void dumpInfo() {
        try {
            log("Perangkat: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                boolean back = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
                log("== Kamera " + id + (back ? " (belakang)" : " (depan/lain)"));
                log("Level: " + levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));

                boolean manualSensor = false, highSpeed = false;
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if (caps != null) {
                    for (int cap : caps) {
                        if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) manualSensor = true;
                        if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) highSpeed = true;
                    }
                }
                log("MANUAL_SENSOR: " + yn(manualSensor) + " | HIGH_SPEED: " + yn(highSpeed));

                Boolean aeLockOk = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
                Boolean awbLockOk = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
                log("AE lock tersedia: " + yn(aeLockOk != null && aeLockOk)
                        + " | AWB lock tersedia: " + yn(awbLockOk != null && awbLockOk));

                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] hs = map == null ? null : map.getHighSpeedVideoSizes();
                if (hs == null || hs.length == 0) {
                    log("Ukuran high-speed: TIDAK ADA");
                } else {
                    for (Size s : hs) {
                        Range<Integer>[] rs = map.getHighSpeedVideoFpsRangesFor(s);
                        log("High-speed " + s + " : " + Arrays.toString(rs));
                    }
                }
            }
            log("");
        } catch (Exception e) {
            log("Error cek kemampuan: " + e);
        }
    }

    /**
     * Memilih kamera/ukuran/rentang FPS untuk fps yang diminta.
     * Return: -1 = tidak ada, 0 = siap (ukuran preview sama), 1 = ukuran preview berubah.
     */
    private int selectFor(int fps) {
        try {
            Size bestSize = null;
            Range<Integer> bestRange = null;
            String bestId = null;
            CameraCharacteristics bestChars = null;
            long bestScore = Long.MAX_VALUE;

            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Size[] sizes = map.getHighSpeedVideoSizes();
                if (sizes == null) continue;
                for (Size s : sizes) {
                    Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(s);
                    Range<Integer> pick = null;
                    for (Range<Integer> r : ranges) {
                        if (r.getUpper() == fps && (pick == null || r.getLower() > pick.getLower())) {
                            pick = r;
                        }
                    }
                    if (pick == null) continue;
                    boolean is720 = s.getWidth() == 1280 && s.getHeight() == 720;
                    long score = is720 ? 0L : (long) s.getWidth() * s.getHeight();
                    if (score < bestScore) {
                        bestScore = score;
                        bestSize = s;
                        bestRange = pick;
                        bestId = id;
                        bestChars = c;
                    }
                }
            }

            if (bestSize == null) {
                log("Tidak ada mode high-speed " + fps + " FPS di kamera belakang.");
                return -1;
            }
            boolean sizeChanged = recSize == null || !recSize.equals(bestSize);
            camId = bestId;
            chars = bestChars;
            recSize = bestSize;
            recRange = bestRange;
            targetFps = fps;

            if (sizeChanged) {
                final Size fs = recSize;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        surfaceView.getHolder().setFixedSize(fs.getWidth(), fs.getHeight());
                    }
                });
                return 1;
            }
            return 0;
        } catch (Exception e) {
            log("Error memilih konfigurasi: " + e);
            return -1;
        }
    }

    // ------------------------------------------------------------- merekam

    private void endIdle() {
        recording = false;
        setButtons(true);
    }

    private void startRecording(final int fps, final boolean lock) {
        if (recording) return;
        recording = true;
        setButtons(false);
        camHandler.post(new Runnable() {
            @Override public void run() {
                int r = selectFor(fps);
                if (r < 0) { endIdle(); return; }
                if (r == 1) {
                    log("Ukuran preview berubah ke " + recSize + ". Tunggu 2 detik lalu tekan tombol lagi.");
                    endIdle();
                    return;
                }
                Rect fr = surfaceView.getHolder().getSurfaceFrame();
                if (!surfaceView.getHolder().getSurface().isValid()
                        || fr.width() != recSize.getWidth() || fr.height() != recSize.getHeight()) {
                    log("Preview belum siap (" + fr.width() + "x" + fr.height()
                            + "). Tunggu 1-2 detik lalu tekan lagi.");
                    endIdle();
                    return;
                }
                frames.clear();
                aeLockUsed = lock;
                lockFrameIndex = -1;
                log("--- Mulai rekam " + fps + " FPS " + (lock ? "AE LOCK" : "AUTO") + " " + recSize
                        + " rentang " + recRange + " ---");
                openCameraAndRecord();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void openCameraAndRecord() {
        try {
            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    fail("Kamera terputus.");
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    fail("Error kamera kode " + error);
                }
            }, camHandler);
        } catch (Exception e) {
            fail("openCamera: " + e);
        }
    }

    private int orientHint() {
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = so == null ? 90 : so;
        @SuppressWarnings("deprecation")
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        int deg = rot == Surface.ROTATION_90 ? 90
                : rot == Surface.ROTATION_180 ? 180
                : rot == Surface.ROTATION_270 ? 270 : 0;
        return (sensor - deg + 360) % 360;
    }

    private void prepareRecorder() throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME,
                "HFR_" + targetFps + (aeLockUsed ? "_lock_" : "_auto_") + System.currentTimeMillis() + ".mp4");
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HFRTest");
        videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (videoUri == null) throw new IllegalStateException("Gagal membuat file video");
        videoPfd = getContentResolver().openFileDescriptor(videoUri, "rw");

        recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(videoPfd.getFileDescriptor());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoEncodingBitRate(targetFps >= 240 ? 60_000_000 : 40_000_000);
        recorder.setVideoFrameRate(targetFps);
        recorder.setVideoSize(recSize.getWidth(), recSize.getHeight());
        recorder.setOrientationHint(orientHint());
        recorder.prepare();
    }

    private void createSession() {
        try {
            prepareRecorder();
            prevSurface = surfaceView.getHolder().getSurface();
            recSurface = recorder.getSurface();
            List<Surface> outs = Arrays.asList(prevSurface, recSurface);
            cameraDevice.createConstrainedHighSpeedCaptureSession(outs,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            hsSession = (CameraConstrainedHighSpeedCaptureSession) session;
                            startBurst();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            fail("Konfigurasi sesi high-speed " + targetFps + " FPS DITOLAK oleh driver.");
                        }
                    }, camHandler);
        } catch (Exception e) {
            fail("createSession: " + e);
        }
    }

    private List<CaptureRequest> buildRequests(boolean lockNow) throws Exception {
        CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recRange);
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (lockNow) {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true);
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        }
        b.addTarget(prevSurface);
        b.addTarget(recSurface);
        return hsSession.createHighSpeedRequestList(b.build());
    }

    private void startBurst() {
        try {
            hsSession.setRepeatingBurst(buildRequests(false), captureCb, camHandler);
            recorder.start();

            if (aeLockUsed) {
                camHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            if (recording && hsSession != null) {
                                lockFrameIndex = frames.size();
                                hsSession.setRepeatingBurst(buildRequests(true), captureCb, camHandler);
                                log("AE+AWB dikunci pada frame ke-" + lockFrameIndex);
                            }
                        } catch (Exception e) {
                            log("Gagal mengunci AE/AWB: " + e);
                        }
                    }
                }, LOCK_DELAY_MS);
            }

            camHandler.postDelayed(new Runnable() {
                @Override public void run() { finishRecording(); }
            }, REC_SECONDS * 1000L);
        } catch (Exception e) {
            fail("startBurst: " + e);
        }
    }

    private void finishRecording() {
        if (!recording) return;
        try { hsSession.stopRepeating(); } catch (Exception e) { log("stopRepeating: " + e); }
        try { recorder.stop(); } catch (RuntimeException e) { log("recorder.stop gagal: " + e); }
        Uri uri = videoUri;
        cleanup();
        analyze(uri);
        endIdle();
    }

    private void fail(String msg) {
        log("GAGAL: " + msg);
        Uri u = videoUri;
        cleanup();
        if (u != null) {
            try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
        }
        endIdle();
    }

    private void cleanup() {
        try { if (hsSession != null) hsSession.close(); } catch (Exception ignored) { }
        hsSession = null;
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) { }
        cameraDevice = null;
        try {
            if (recorder != null) { recorder.reset(); recorder.release(); }
        } catch (Exception ignored) { }
        recorder = null;
        try { if (videoPfd != null) videoPfd.close(); } catch (Exception ignored) { }
        videoPfd = null;
        videoUri = null;
    }

    // ------------------------------------------------------------- analisis

    private void analyze(Uri uri) {
        List<long[]> f;
        synchronized (frames) { f = new ArrayList<long[]>(frames); }
        int n = f.size();
        log("Hasil capture diterima: " + n + " (jika " + targetFps + " FPS penuh, sekitar "
                + (targetFps * REC_SECONDS) + ")");

        if (n >= 3) {
            long[] d = new long[n - 1];
            for (int i = 0; i < n - 1; i++) d[i] = f.get(i + 1)[0] - f.get(i)[0];
            long[] sorted = d.clone();
            Arrays.sort(sorted);
            long med = sorted[sorted.length / 2];
            int jumps = 0;
            for (long x : d) if (x > 1.5 * med) jumps++;
            double span = (f.get(n - 1)[0] - f.get(0)[0]) / 1e9;
            log(String.format(Locale.US, "Median jarak antar hasil: %.2f ms (= %.1f /detik)", med / 1e6, 1e9 / med));
            log(String.format(Locale.US, "Rata-rata: %.1f hasil/detik | lonjakan interval: %d", (n - 1) / span, jumps));

            // ringkasan exposure per detik
            long t0 = f.get(0)[0];
            int secs = (int) ((f.get(n - 1)[0] - t0) / 1_000_000_000L) + 1;
            long[] minE = new long[secs], maxE = new long[secs], minI = new long[secs], maxI = new long[secs];
            for (int s = 0; s < secs; s++) {
                minE[s] = Long.MAX_VALUE; maxE[s] = Long.MIN_VALUE;
                minI[s] = Long.MAX_VALUE; maxI[s] = Long.MIN_VALUE;
            }
            for (long[] r : f) {
                int s = (int) ((r[0] - t0) / 1_000_000_000L);
                if (s < 0 || s >= secs) continue;
                minE[s] = Math.min(minE[s], r[1]); maxE[s] = Math.max(maxE[s], r[1]);
                minI[s] = Math.min(minI[s], r[2]); maxI[s] = Math.max(maxI[s], r[2]);
            }
            log("Exposure & ISO per detik:");
            for (int s = 0; s < secs; s++) {
                if (minE[s] == Long.MAX_VALUE) continue;
                log(String.format(Locale.US, " d%d: exp %.2f-%.2f ms | ISO %d-%d",
                        s, minE[s] / 1e6, maxE[s] / 1e6, minI[s], maxI[s]));
            }

            if (aeLockUsed && lockFrameIndex >= 0) {
                int from = lockFrameIndex + targetFps / 2;
                if (from < n - 1) {
                    long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE, ilo = Long.MAX_VALUE, ihi = Long.MIN_VALUE;
                    for (int i = from; i < n; i++) {
                        lo = Math.min(lo, f.get(i)[1]); hi = Math.max(hi, f.get(i)[1]);
                        ilo = Math.min(ilo, f.get(i)[2]); ihi = Math.max(ihi, f.get(i)[2]);
                    }
                    boolean stable = lo > 0 && hi <= lo * 1.05;
                    log(String.format(Locale.US, "SETELAH LOCK: exposure %.3f-%.3f ms, ISO %d-%d => %s",
                            lo / 1e6, hi / 1e6, ilo, ihi,
                            stable ? "STABIL (lock dipatuhi)" : "MASIH BERUBAH (lock diabaikan)"));
                } else {
                    log("Data setelah lock terlalu sedikit untuk dianalisis.");
                }
            }
        }

        if (uri != null) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, uri);
                String fc = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                log("File video: " + w + "x" + h + ", frame=" + fc + ", durasi=" + dur + " ms");
                if (fc != null && dur != null) {
                    double fps = Double.parseDouble(fc) / (Double.parseDouble(dur) / 1000.0);
                    log(String.format(Locale.US, "FPS NYATA di file video: %.1f", fps));
                }
            } catch (Exception e) {
                log("Gagal membaca file video: " + e);
            } finally {
                try { mmr.release(); } catch (Exception ignored) { }
            }
            log("Video tersimpan di Movies/HFRTest (cek di Galeri / File Manager).");
        }
        log("--- Selesai. Salin seluruh teks log ini dan kirim ke Claude ---\n");
    }
}
