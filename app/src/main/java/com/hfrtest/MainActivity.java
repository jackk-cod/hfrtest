package com.hfrtest;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * HFR Lab v3: menjalankan serangkaian tes otomatis (120/240 FPS, EV, manual, tanpa preview,
 * sesi biasa, HEVC) lalu menampilkan ringkasan satu baris per tes.
 */
public class MainActivity extends Activity {

    private static final int REC_SECONDS = 4;
    private static final long MANUAL_EXPOSURE_NS = 1_000_000L; // 1/1000 s
    private static final int MANUAL_ISO = 800;

    private static final int M_AUTO = 0;
    private static final int M_EV = 1;
    private static final int M_CTRLOFF = 2;
    private static final int M_NORMAL_MANUAL = 3;

    private static class TestCase {
        final String id, title;
        final boolean hs, preview, hevc;
        final int fps, mode;

        TestCase(String id, String title, boolean hs, int fps, boolean preview, boolean hevc, int mode) {
            this.id = id;
            this.title = title;
            this.hs = hs;
            this.fps = fps;
            this.preview = preview;
            this.hevc = hevc;
            this.mode = mode;
        }
    }

    private final List<TestCase> tests = new ArrayList<TestCase>();
    private int testIndex = 0;
    private TestCase cur;
    private int token = 0;
    private volatile boolean running = false;
    private volatile boolean testActive = false;
    private final StringBuilder report = new StringBuilder();

    private CameraManager manager;
    private HandlerThread camThread;
    private Handler camHandler;

    private SurfaceView surfaceView;
    private TextView logView;
    private ScrollView scroll;
    private Button btnRun, btnCopy, btnInfo;

    private String camId;
    private CameraCharacteristics chars;
    private Size recSize;
    private Range<Integer> recRange;
    private int targetFps = 120;
    private long requestedExpNs = 0;
    private String note = "";

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private CameraConstrainedHighSpeedCaptureSession hsSession;
    private MediaRecorder recorder;
    private Uri videoUri;
    private ParcelFileDescriptor videoPfd;
    private Surface prevSurface, recSurface;
    private CameraCaptureSession.CaptureCallback captureCb;

    // tiap elemen: {sensorTimestampNs, exposureNs, iso}
    private final List<long[]> frames = Collections.synchronizedList(new ArrayList<long[]>());

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tests.add(new TestCase("T1", "HS 120 FPS AUTO (pembanding)", true, 120, true, false, M_AUTO));
        tests.add(new TestCase("T2", "HS 120 FPS EV minimum (shutter via kompensasi)", true, 120, true, false, M_EV));
        tests.add(new TestCase("T3", "HS 120 FPS CONTROL_MODE_OFF manual 1/1000", true, 120, true, false, M_CTRLOFF));
        tests.add(new TestCase("T4", "HS 120 FPS tanpa preview", true, 120, false, false, M_AUTO));
        tests.add(new TestCase("T5", "SESI BIASA manual 1/1000 (bukan high-speed)", false, 120, false, false, M_NORMAL_MANUAL));
        tests.add(new TestCase("T6", "HS 240 FPS preview + HEVC", true, 240, true, true, M_AUTO));
        tests.add(new TestCase("T7", "HS 240 FPS tanpa preview H264", true, 240, false, false, M_AUTO));
        tests.add(new TestCase("T8", "HS 240 FPS tanpa preview HEVC", true, 240, false, true, M_AUTO));

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
        running = false;
        testActive = false;
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

        btnRun = makeButton("JALANKAN SEMUA TES (sekitar 2 menit)");
        btnCopy = makeButton("Salin laporan");
        btnInfo = makeButton("Cek kemampuan kamera");
        right.addView(btnRun);
        right.addView(btnCopy);
        right.addView(btnInfo);

        btnRun.setOnClickListener(v -> runAll());
        btnCopy.setOnClickListener(v -> copyReport());
        btnInfo.setOnClickListener(v -> camHandler.post(() -> dumpInfo()));

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
        runOnUiThread(() -> {
            btnRun.setEnabled(enabled);
            btnInfo.setEnabled(enabled);
        });
    }

    private void log(final String s) {
        runOnUiThread(() -> {
            logView.append(s + "\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void clearLog() {
        runOnUiThread(() -> logView.setText(""));
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("laporan HFR", logView.getText().toString()));
        Toast.makeText(this, "Laporan disalin. Tempel ke Claude.", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------- info & seleksi

    private void init() {
        camHandler.post(() -> {
            log("HFR Lab v3");
            dumpInfo();
            selectFor(120);
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
                log("EV range: " + c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                        + " langkah " + c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP));

                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null && back) {
                    try {
                        long md = map.getOutputMinFrameDuration(MediaRecorder.class, new Size(1280, 720));
                        if (md > 0) {
                            log(String.format(Locale.US,
                                    "Sesi biasa 720p: durasi frame minimum %.2f ms (maks %.1f FPS)", md / 1e6, 1e9 / md));
                        }
                    } catch (Exception e) {
                        log("Sesi biasa 720p min frame duration: " + e);
                    }
                }
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

    /** -1 = tidak ada, 0 = siap, 1 = ukuran preview berubah. */
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
                runOnUiThread(() -> surfaceView.getHolder().setFixedSize(fs.getWidth(), fs.getHeight()));
                return 1;
            }
            return 0;
        } catch (Exception e) {
            log("Error memilih konfigurasi: " + e);
            return -1;
        }
    }

    // --------------------------------------------------------- jalankan tes

    private void runAll() {
        if (running) return;
        if (recSize == null) {
            log("Belum ada konfigurasi high-speed. Tekan 'Cek kemampuan kamera' dulu.");
            return;
        }
        Rect fr = surfaceView.getHolder().getSurfaceFrame();
        if (!surfaceView.getHolder().getSurface().isValid()
                || fr.width() != recSize.getWidth() || fr.height() != recSize.getHeight()) {
            log("Preview belum siap (" + fr.width() + "x" + fr.height() + "). Tunggu 2 detik lalu tekan lagi.");
            return;
        }
        running = true;
        setButtons(false);
        report.setLength(0);
        testIndex = 0;
        clearLog();
        camHandler.post(() -> {
            log("HFR Lab v3 - MULAI SEMUA TES");
            log("Arahkan HP ke tempat TERANG dan tahan stabil sampai selesai.");
            dumpInfo();
            selectFor(120);
            nextTest();
        });
    }

    private void nextTest() {
        if (!running) return;
        if (testIndex >= tests.size()) {
            finishAll();
            return;
        }
        cur = tests.get(testIndex++);
        log("[" + testIndex + "/" + tests.size() + "] " + cur.id + " " + cur.title);
        frames.clear();
        note = "";
        requestedExpNs = 0;
        testActive = true;
        token++;
        final int t = token;
        camHandler.postDelayed(() -> {
            if (testActive && token == t) endTest("GAGAL: waktu habis (tes macet)", true);
        }, 20000);

        targetFps = cur.fps;
        if (cur.hs) {
            int r = selectFor(cur.fps);
            if (r < 0) { endTest("GAGAL: tidak ada mode high-speed " + cur.fps + " FPS", true); return; }
            if (r == 1) { endTest("GAGAL: ukuran preview berubah, ulangi", true); return; }
        }
        openCameraAndRecord();
    }

    private void finishAll() {
        log("");
        log("=== RINGKASAN ===");
        log(report.toString());
        log("=== SELESAI. Tekan 'Salin laporan' lalu tempel ke Claude ===");
        running = false;
        setButtons(true);
    }

    private void endTest(String line, boolean deleteVideo) {
        if (!testActive) return;
        testActive = false;
        token++;
        Uri u = videoUri;
        cleanup();
        if (deleteVideo && u != null) {
            try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
        }
        String full = cur.id + " | " + line;
        report.append(full).append("\n");
        log(">> " + full);
        camHandler.postDelayed(() -> nextTest(), 3000);
    }

    @SuppressLint("MissingPermission")
    private void openCameraAndRecord() {
        try {
            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (!testActive) { camera.close(); return; }
                    cameraDevice = camera;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    endTest("GAGAL: kamera terputus", true);
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    endTest("GAGAL: error kamera kode " + error, true);
                }
            }, camHandler);
        } catch (Exception e) {
            endTest("GAGAL: openCamera: " + e, true);
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
        v.put(MediaStore.Video.Media.DISPLAY_NAME, "HFRLAB_" + cur.id + "_" + System.currentTimeMillis() + ".mp4");
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HFRTest");
        videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (videoUri == null) throw new IllegalStateException("Gagal membuat file video");
        videoPfd = getContentResolver().openFileDescriptor(videoUri, "rw");

        recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(videoPfd.getFileDescriptor());
        recorder.setVideoEncoder(cur.hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        recorder.setVideoEncodingBitRate(cur.fps >= 240 ? 60_000_000 : 40_000_000);
        recorder.setVideoFrameRate(cur.fps);
        recorder.setVideoSize(recSize.getWidth(), recSize.getHeight());
        recorder.setOrientationHint(orientHint());
        recorder.prepare();
    }

    private void createSession() {
        try {
            if (cur.preview && !surfaceView.getHolder().getSurface().isValid()) {
                endTest("GAGAL: preview belum siap", true);
                return;
            }
            prepareRecorder();
            prevSurface = cur.preview ? surfaceView.getHolder().getSurface() : null;
            recSurface = recorder.getSurface();

            if (cur.hs) {
                List<Surface> outs = new ArrayList<Surface>();
                if (prevSurface != null) outs.add(prevSurface);
                outs.add(recSurface);
                cameraDevice.createConstrainedHighSpeedCaptureSession(outs,
                        new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                if (!testActive) return;
                                session = s;
                                hsSession = (CameraConstrainedHighSpeedCaptureSession) s;
                                startHighSpeed();
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) {
                                endTest("GAGAL: sesi high-speed DITOLAK driver", true);
                            }
                        }, camHandler);
            } else {
                cameraDevice.createCaptureSession(Arrays.asList(recSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                if (!testActive) return;
                                session = s;
                                startNormal();
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) {
                                endTest("GAGAL: sesi biasa DITOLAK driver", true);
                            }
                        }, camHandler);
            }
        } catch (Exception e) {
            endTest("GAGAL: createSession: " + e, true);
        }
    }

    private void setManualSensor(CaptureRequest.Builder b) {
        long exp = MANUAL_EXPOSURE_NS;
        Range<Long> er = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (er != null) exp = Math.max(er.getLower(), Math.min(er.getUpper(), exp));
        int iso = MANUAL_ISO;
        Range<Integer> ir = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (ir != null) iso = Math.max(ir.getLower(), Math.min(ir.getUpper(), iso));
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / cur.fps);
        requestedExpNs = exp;
        note = " | diminta exp " + exp / 1000 + "us ISO " + iso;
    }

    private void applyMode(CaptureRequest.Builder b) {
        switch (cur.mode) {
            case M_EV: {
                Range<Integer> r = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                int ev = r == null ? 0 : r.getLower();
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
                note = " | diminta EV idx " + ev;
                break;
            }
            case M_CTRLOFF:
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF);
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                setManualSensor(b);
                break;
            case M_NORMAL_MANUAL:
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                setManualSensor(b);
                break;
            default:
                break;
        }
    }

    private void scheduleFinish() {
        final int t = token;
        camHandler.postDelayed(() -> {
            if (testActive && token == t) finishCurrent();
        }, REC_SECONDS * 1000L);
    }

    private void startHighSpeed() {
        try {
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recRange);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            if (prevSurface != null) b.addTarget(prevSurface);
            b.addTarget(recSurface);
            applyMode(b);
            List<CaptureRequest> list = hsSession.createHighSpeedRequestList(b.build());
            hsSession.setRepeatingBurst(list, captureCb, camHandler);
            recorder.start();
            scheduleFinish();
        } catch (Exception e) {
            endTest("GAGAL: startHighSpeed: " + e, true);
        }
    }

    private void startNormal() {
        try {
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            b.addTarget(recSurface);
            applyMode(b);
            session.setRepeatingRequest(b.build(), captureCb, camHandler);
            recorder.start();
            scheduleFinish();
        } catch (Exception e) {
            endTest("GAGAL: startNormal: " + e, true);
        }
    }

    private void finishCurrent() {
        try { if (session != null) session.stopRepeating(); } catch (Exception e) { log("stopRepeating: " + e); }
        try { recorder.stop(); } catch (RuntimeException e) { log("recorder.stop gagal: " + e); }
        Uri uri = videoUri;
        cleanup();
        String line = analyzeLine(uri);
        endTest(line, false);
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

    private String analyzeLine(Uri uri) {
        List<long[]> f;
        synchronized (frames) { f = new ArrayList<long[]>(frames); }
        int n = f.size();
        StringBuilder sb = new StringBuilder("OK");

        double fileFps = -1;
        String fileInfo = "file ?";
        if (uri != null) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, uri);
                String fc = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (fc != null && dur != null) {
                    double frameCount = Double.parseDouble(fc);
                    double sec = Double.parseDouble(dur) / 1000.0;
                    fileFps = frameCount / sec;
                    fileInfo = String.format(Locale.US, "file %.1f FPS (%s frame/%.1f dtk, %sx%s)",
                            fileFps, fc, sec, w, h);
                }
            } catch (Exception e) {
                fileInfo = "file gagal dibaca";
            } finally {
                try { mmr.release(); } catch (Exception ignored) { }
            }
        }
        sb.append(" | ").append(fileInfo);
        if (fileFps > 0) {
            sb.append(fileFps >= 0.95 * cur.fps ? " [FPS PENUH]" : " [FPS KURANG]");
        }

        if (n >= 3) {
            long[] d = new long[n - 1];
            for (int i = 0; i < n - 1; i++) d[i] = f.get(i + 1)[0] - f.get(i)[0];
            long[] sorted = d.clone();
            Arrays.sort(sorted);
            long med = sorted[sorted.length / 2];
            int jumps = 0;
            for (long x : d) if (x > 1.5 * med) jumps++;
            double span = (f.get(n - 1)[0] - f.get(0)[0]) / 1e9;
            sb.append(String.format(Locale.US, " | hasil %.1f/dtk median %.2fms lonjakan %d",
                    (n - 1) / span, med / 1e6, jumps));

            long minE = Long.MAX_VALUE, maxE = Long.MIN_VALUE;
            long minI = Long.MAX_VALUE, maxI = Long.MIN_VALUE;
            int zeros = 0;
            for (long[] r : f) {
                if (r[1] <= 0 || r[2] <= 0) { zeros++; continue; }
                minE = Math.min(minE, r[1]); maxE = Math.max(maxE, r[1]);
                minI = Math.min(minI, r[2]); maxI = Math.max(maxI, r[2]);
            }
            if (minE != Long.MAX_VALUE) {
                sb.append(String.format(Locale.US, " | exp %.3f-%.3f ms (1/%d-1/%d s)",
                        minE / 1e6, maxE / 1e6, 1_000_000_000L / maxE, 1_000_000_000L / minE));
                sb.append(" | ISO ").append(minI).append("-").append(maxI);
                if (requestedExpNs > 0) {
                    boolean honored = Math.abs(minE - requestedExpNs) <= requestedExpNs * 0.1
                            && Math.abs(maxE - requestedExpNs) <= requestedExpNs * 0.1;
                    sb.append(honored ? " [MANUAL DIPATUHI]" : " [MANUAL DIABAIKAN]");
                }
            }
            if (zeros > 0) sb.append(" | data nol ").append(zeros);
        } else {
            sb.append(" | hasil capture hanya ").append(n);
        }
        sb.append(note);
        return sb.toString();
    }
}
