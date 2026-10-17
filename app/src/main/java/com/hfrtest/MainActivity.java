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
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
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
 * HFR Recorder v4: rekam 720p 120 FPS (high-speed). Shutter ditampilkan langsung;
 * atur cahaya / EV sampai shutter sesuai, tekan KUNCI, lalu video tetap konsisten.
 */
public class MainActivity extends Activity {

    private static final int FPS = 120;
    private static final int MAX_SECONDS = 30;

    private CameraManager manager;
    private HandlerThread camThread;
    private Handler camHandler;

    private SurfaceView surfaceView;
    private TextView liveView, logView, tvEv;
    private ScrollView scroll;
    private Button btnRec, btnLock, btnEvMinus, btnEvPlus;

    private String camId;
    private CameraCharacteristics chars;
    private Size recSize;
    private Range<Integer> recRange;
    private int evIdx = 0;
    private int evMin = -20;
    private int evMax = 10;

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private CameraConstrainedHighSpeedCaptureSession hsSession;
    private MediaRecorder recorder;
    private Uri videoUri;
    private ParcelFileDescriptor videoPfd;
    private Surface prevSurface, recSurface;
    private CameraCaptureSession.CaptureCallback captureCb;

    private volatile boolean recording = false;
    private volatile boolean live = false;
    private volatile boolean locked = false;
    private volatile long lastExp = -1;
    private volatile long lastIso = -1;
    private long lastUi = 0;
    private int lockIdx = -1;
    private int token = 0;

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
                if (ts == null) return;
                long e = ex == null ? -1 : ex;
                long i = iso == null ? -1 : iso;
                frames.add(new long[]{ts, e, i});
                lastExp = e;
                lastIso = i;
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi > 250) {
                    lastUi = now;
                    updateLive();
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
        live = false;
        recording = false;
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

        liveView = new TextView(this);
        liveView.setTypeface(Typeface.MONOSPACE);
        liveView.setTextSize(20);
        liveView.setText("Tekan MULAI REKAM.\nShutter akan tampil di sini.");
        right.addView(liveView);

        LinearLayout evRow = new LinearLayout(this);
        evRow.setOrientation(LinearLayout.HORIZONTAL);
        btnEvMinus = makeButton("EV -");
        btnEvPlus = makeButton("EV +");
        tvEv = new TextView(this);
        tvEv.setTextSize(16);
        tvEv.setGravity(android.view.Gravity.CENTER);
        evRow.addView(btnEvMinus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        evRow.addView(tvEv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        evRow.addView(btnEvPlus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        right.addView(evRow);

        btnRec = makeButton("MULAI REKAM");
        btnLock = makeButton("KUNCI exposure sekarang");
        btnLock.setEnabled(false);
        right.addView(btnRec);
        right.addView(btnLock);

        btnRec.setOnClickListener(v -> {
            if (!recording) startRecording();
            else camHandler.post(() -> finishRecording());
        });
        btnLock.setOnClickListener(v -> toggleLock());
        btnEvMinus.setOnClickListener(v -> changeEv(-5));
        btnEvPlus.setOnClickListener(v -> changeEv(5));

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        right.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        setUi();
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private void setUi() {
        runOnUiThread(() -> {
            btnRec.setText(recording ? "STOP" : "MULAI REKAM");
            btnLock.setEnabled(live);
            btnLock.setText(locked ? "BUKA KUNCI" : "KUNCI exposure sekarang");
            btnEvMinus.setEnabled(!locked);
            btnEvPlus.setEnabled(!locked);
            tvEv.setText(String.format(Locale.US, "EV %.1f", evIdx / 10.0));
        });
    }

    private void updateLive() {
        final long e = lastExp;
        final long i = lastIso;
        final boolean lk = locked;
        final String s;
        if (e > 0) {
            s = String.format(Locale.US, "SHUTTER 1/%d s\n(%.2f ms)\nISO %d\n%s",
                    Math.round(1e9 / e), e / 1e6, i, lk ? "[TERKUNCI]" : "[AUTO]");
        } else {
            s = "menunggu data kamera...";
        }
        runOnUiThread(() -> liveView.setText(s));
    }

    private void log(final String s) {
        runOnUiThread(() -> {
            logView.append(s + "\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ----------------------------------------------------------- persiapan

    private void init() {
        camHandler.post(() -> {
            if (selectConfig()) {
                log("Siap: kamera " + camId + ", " + recSize + " @ " + FPS + " FPS (high-speed).");
                log("Cara pakai: arahkan ke objek, tekan MULAI REKAM, atur cahaya/EV sampai shutter sesuai,");
                log("tekan KUNCI, lalu lanjutkan. Bagian video sebelum kunci dibuang saat analisis.");
            } else {
                log("Kamera belakang tidak mendukung high-speed " + FPS + " FPS.");
            }
        });
    }

    private boolean selectConfig() {
        try {
            Size bestSize = null;
            Range<Integer> bestRange = null;
            String bestId = null;
            CameraCharacteristics bestChars = null;

            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Size[] sizes = map.getHighSpeedVideoSizes();
                if (sizes == null) continue;
                for (Size s : sizes) {
                    if (!(s.getWidth() == 1280 && s.getHeight() == 720)) continue;
                    Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(s);
                    Range<Integer> pick = null;
                    for (Range<Integer> r : ranges) {
                        if (r.getUpper() == FPS && (pick == null || r.getLower() > pick.getLower())) {
                            pick = r;
                        }
                    }
                    if (pick != null) {
                        bestSize = s; bestRange = pick; bestId = id; bestChars = c;
                    }
                }
            }
            if (bestSize == null) return false;

            camId = bestId;
            chars = bestChars;
            recSize = bestSize;
            recRange = bestRange;

            Range<Integer> evr = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (evr != null) {
                evMin = Math.max(evr.getLower(), -20);
                evMax = Math.min(evr.getUpper(), 10);
            }
            final Size fs = recSize;
            runOnUiThread(() -> surfaceView.getHolder().setFixedSize(fs.getWidth(), fs.getHeight()));
            return true;
        } catch (Exception e) {
            log("Error memilih konfigurasi: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------- merekam

    private void startRecording() {
        if (recSize == null) {
            log("Belum siap. Tunggu sebentar.");
            return;
        }
        Rect fr = surfaceView.getHolder().getSurfaceFrame();
        if (!surfaceView.getHolder().getSurface().isValid()
                || fr.width() != recSize.getWidth() || fr.height() != recSize.getHeight()) {
            log("Preview belum siap. Tunggu 2 detik lalu tekan lagi.");
            return;
        }
        recording = true;
        locked = false;
        lockIdx = -1;
        lastExp = -1;
        frames.clear();
        token++;
        setUi();
        log("--- Mulai (maks " + MAX_SECONDS + " detik) ---");
        camHandler.post(() -> openCamera());
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (!recording) { camera.close(); return; }
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
        v.put(MediaStore.Video.Media.DISPLAY_NAME, "HFR_" + System.currentTimeMillis() + ".mp4");
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
        recorder.setVideoEncodingBitRate(40_000_000);
        recorder.setVideoFrameRate(FPS);
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
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (!recording) return;
                            session = s;
                            hsSession = (CameraConstrainedHighSpeedCaptureSession) s;
                            startBurst();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            fail("Sesi high-speed ditolak driver.");
                        }
                    }, camHandler);
        } catch (Exception e) {
            fail("createSession: " + e);
        }
    }

    private List<CaptureRequest> buildRequests() throws Exception {
        CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recRange);
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIdx);
        if (locked) {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true);
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        }
        b.addTarget(prevSurface);
        b.addTarget(recSurface);
        return hsSession.createHighSpeedRequestList(b.build());
    }

    private void startBurst() {
        try {
            hsSession.setRepeatingBurst(buildRequests(), captureCb, camHandler);
            recorder.start();
            live = true;
            setUi();
            log("Merekam. Atur cahaya/EV, lalu tekan KUNCI.");
            final int t = token;
            camHandler.postDelayed(() -> {
                if (recording && live && token == t) {
                    log("Batas " + MAX_SECONDS + " detik tercapai, berhenti otomatis.");
                    finishRecording();
                }
            }, MAX_SECONDS * 1000L);
        } catch (Exception e) {
            fail("startBurst: " + e);
        }
    }

    private void reissue() {
        camHandler.post(() -> {
            if (!live || hsSession == null) return;
            try {
                hsSession.setRepeatingBurst(buildRequests(), captureCb, camHandler);
            } catch (Exception e) {
                log("Gagal memperbarui pengaturan: " + e);
            }
        });
    }

    private void changeEv(int delta) {
        evIdx = Math.max(evMin, Math.min(evMax, evIdx + delta));
        setUi();
        if (live) reissue();
    }

    private void toggleLock() {
        camHandler.post(() -> {
            if (!live || hsSession == null) return;
            try {
                locked = !locked;
                if (locked) {
                    lockIdx = frames.size();
                    log("Exposure DIKUNCI pada shutter sekitar 1/"
                            + (lastExp > 0 ? Math.round(1e9 / lastExp) : 0) + " s.");
                } else {
                    lockIdx = -1;
                    log("Kunci dibuka (kembali otomatis).");
                }
                hsSession.setRepeatingBurst(buildRequests(), captureCb, camHandler);
                setUi();
                updateLive();
            } catch (Exception e) {
                log("Gagal mengubah kunci: " + e);
            }
        });
    }

    private void finishRecording() {
        if (!recording) return;
        token++;
        boolean wasLive = live;
        live = false;
        if (wasLive) {
            try { if (session != null) session.stopRepeating(); } catch (Exception e) { log("stopRepeating: " + e); }
            try { recorder.stop(); } catch (RuntimeException e) { log("recorder.stop gagal: " + e); }
            Uri uri = videoUri;
            cleanup();
            analyze(uri);
        } else {
            Uri u = videoUri;
            cleanup();
            if (u != null) {
                try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
            }
            log("Dibatalkan sebelum mulai merekam.");
        }
        recording = false;
        locked = false;
        setUi();
    }

    private void fail(String msg) {
        log("GAGAL: " + msg);
        Uri u = videoUri;
        live = false;
        cleanup();
        if (u != null) {
            try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
        }
        recording = false;
        locked = false;
        setUi();
    }

    private void cleanup() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
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
        log("--- Selesai ---");

        if (uri != null) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, uri);
                String fc = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (fc != null && dur != null) {
                    double sec = Double.parseDouble(dur) / 1000.0;
                    log(String.format(Locale.US, "File: %s frame, %.1f detik, rata-rata %.1f FPS",
                            fc, sec, Double.parseDouble(fc) / sec));
                }
            } catch (Exception e) {
                log("Gagal membaca file video: " + e);
            } finally {
                try { mmr.release(); } catch (Exception ignored) { }
            }
            log("Tersimpan di Movies/HFRTest.");
        }

        if (n >= 3) {
            long[] d = new long[n - 1];
            for (int i = 0; i < n - 1; i++) d[i] = f.get(i + 1)[0] - f.get(i)[0];
            long[] sorted = d.clone();
            Arrays.sort(sorted);
            long med = sorted[sorted.length / 2];
            int jumps = 0;
            for (long x : d) if (x > 1.5 * med) jumps++;
            log(String.format(Locale.US, "Jarak frame median %.2f ms, lonjakan %d (frame terlewat/telat)",
                    med / 1e6, jumps));
        }

        if (lockIdx >= 0 && lockIdx < n) {
            double t = (f.get(lockIdx)[0] - f.get(0)[0]) / 1e9;
            int from = Math.min(n - 1, lockIdx + FPS / 2);
            long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE, ilo = Long.MAX_VALUE, ihi = Long.MIN_VALUE;
            for (int i = from; i < n; i++) {
                long e = f.get(i)[1], iso = f.get(i)[2];
                if (e <= 0) continue;
                lo = Math.min(lo, e); hi = Math.max(hi, e);
                ilo = Math.min(ilo, iso); ihi = Math.max(ihi, iso);
            }
            log(String.format(Locale.US, "Kunci ditekan pada detik %.1f dari awal video.", t));
            if (lo != Long.MAX_VALUE) {
                log(String.format(Locale.US, "Setelah kunci: shutter 1/%d - 1/%d s (%.3f-%.3f ms), ISO %d-%d",
                        Math.round(1e9 / hi), Math.round(1e9 / lo), lo / 1e6, hi / 1e6, ilo, ihi));
                log(hi <= lo * 1.05 ? "=> STABIL. Pakai video SETELAH detik " + String.format(Locale.US, "%.1f", t + 0.5)
                        : "=> MASIH BERUBAH. Kunci tidak sepenuhnya dipatuhi.");
            }
        } else {
            log("Exposure tidak dikunci pada rekaman ini.");
        }
        log("");
    }
}
