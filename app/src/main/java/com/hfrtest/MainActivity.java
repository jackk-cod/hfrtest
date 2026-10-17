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
 * Aplikasi UJI: cek dukungan high-speed 120 FPS + rekam 5 detik
 * (mode AUTO vs mode MANUAL exposure 1/1000 s) dan laporkan hasilnya.
 */
public class MainActivity extends Activity {

    private static final int TARGET_FPS = 120;
    private static final int REC_SECONDS = 5;
    private static final long MANUAL_EXPOSURE_NS = 1_000_000L; // 1/1000 s
    private static final int MANUAL_ISO = 800;

    private CameraManager manager;
    private HandlerThread camThread;
    private Handler camHandler;

    private SurfaceView surfaceView;
    private TextView logView;
    private ScrollView scroll;
    private Button btnInfo, btnAuto, btnManual;

    private String camId;
    private CameraCharacteristics chars;
    private Size recSize;
    private Range<Integer> recRange;

    private CameraDevice cameraDevice;
    private CameraConstrainedHighSpeedCaptureSession hsSession;
    private MediaRecorder recorder;
    private Uri videoUri;
    private ParcelFileDescriptor videoPfd;
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
        btnAuto = makeButton("2. Rekam 5 dtk - AUTO");
        btnManual = makeButton("3. Rekam 5 dtk - MANUAL 1/1000");
        right.addView(btnInfo);
        right.addView(btnAuto);
        right.addView(btnManual);

        btnInfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                camHandler.post(new Runnable() {
                    @Override public void run() { dumpInfo(); }
                });
            }
        });
        btnAuto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRecording(false); }
        });
        btnManual.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRecording(true); }
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
                btnAuto.setEnabled(enabled);
                btnManual.setEnabled(enabled);
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
                dumpInfo();
                pickConfig();
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

                Range<Long> er = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                if (er != null) {
                    log("Rentang exposure: " + er.getLower() / 1000 + " us s/d " + er.getUpper() / 1000000 + " ms");
                }
                Range<Integer> isoR = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (isoR != null) log("Rentang ISO: " + isoR.getLower() + " - " + isoR.getUpper());

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

    private void pickConfig() {
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
                        if (r.getUpper() == TARGET_FPS && (pick == null || r.getLower() > pick.getLower())) {
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
                log("HASIL: tidak ada mode high-speed " + TARGET_FPS + " FPS di kamera belakang.");
                setButtons(true);
                return;
            }
            camId = bestId;
            chars = bestChars;
            recSize = bestSize;
            recRange = bestRange;
            log("Dipilih untuk uji: kamera " + camId + ", " + recSize + ", FPS " + recRange);

            final Size fs = recSize;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    surfaceView.getHolder().setFixedSize(fs.getWidth(), fs.getHeight());
                }
            });
        } catch (Exception e) {
            log("Error memilih konfigurasi: " + e);
        }
    }

    // ------------------------------------------------------------- merekam

    private void startRecording(final boolean manual) {
        if (recording) return;
        if (recSize == null) {
            log("Belum ada konfigurasi high-speed " + TARGET_FPS + " FPS yang bisa dipakai.");
            return;
        }
        Rect fr = surfaceView.getHolder().getSurfaceFrame();
        if (!surfaceView.getHolder().getSurface().isValid()
                || fr.width() != recSize.getWidth() || fr.height() != recSize.getHeight()) {
            log("Preview belum siap (" + fr.width() + "x" + fr.height() + "). Tunggu 1-2 detik lalu tekan lagi.");
            return;
        }
        recording = true;
        frames.clear();
        setButtons(false);
        log("--- Mulai rekam " + (manual ? "MANUAL" : "AUTO") + " " + recSize + " FPS " + recRange + " ---");
        camHandler.post(new Runnable() {
            @Override public void run() { openCameraAndRecord(manual); }
        });
    }

    @SuppressLint("MissingPermission")
    private void openCameraAndRecord(final boolean manual) {
        try {
            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession(manual);
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
        recorder.setVideoFrameRate(TARGET_FPS);
        recorder.setVideoSize(recSize.getWidth(), recSize.getHeight());
        recorder.setOrientationHint(orientHint());
        recorder.prepare();
    }

    private void createSession(final boolean manual) {
        try {
            prepareRecorder();
            final Surface prev = surfaceView.getHolder().getSurface();
            final Surface rec = recorder.getSurface();
            List<Surface> outs = Arrays.asList(prev, rec);
            cameraDevice.createConstrainedHighSpeedCaptureSession(outs,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            hsSession = (CameraConstrainedHighSpeedCaptureSession) session;
                            startBurst(manual, prev, rec);
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            fail("Konfigurasi sesi high-speed DITOLAK oleh driver.");
                        }
                    }, camHandler);
        } catch (Exception e) {
            fail("createSession: " + e);
        }
    }

    private void startBurst(boolean manual, Surface prev, Surface rec) {
        try {
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recRange);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            b.addTarget(prev);
            b.addTarget(rec);

            if (manual) {
                long exp = MANUAL_EXPOSURE_NS;
                Range<Long> er = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                if (er != null) exp = Math.max(er.getLower(), Math.min(er.getUpper(), exp));
                int iso = MANUAL_ISO;
                Range<Integer> ir = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (ir != null) iso = Math.max(ir.getLower(), Math.min(ir.getUpper(), iso));

                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / TARGET_FPS);
                log("Diminta: exposure " + exp + " ns (1/" + (1_000_000_000L / exp) + " s), ISO " + iso);
            } else {
                log("Diminta: exposure otomatis (AE)");
            }

            List<CaptureRequest> list = hsSession.createHighSpeedRequestList(b.build());
            hsSession.setRepeatingBurst(list, new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
                    Long ex = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (ts != null) {
                        frames.add(new long[]{ts, ex == null ? -1 : ex, iso == null ? -1 : iso});
                    }
                }
            }, camHandler);

            recorder.start();
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
        recording = false;
        setButtons(true);
    }

    private void fail(String msg) {
        log("GAGAL: " + msg);
        Uri u = videoUri;
        cleanup();
        if (u != null) {
            try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
        }
        recording = false;
        setButtons(true);
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
        log("Hasil capture diterima: " + n + " (jika 120 FPS penuh, sekitar " + (TARGET_FPS * REC_SECONDS) + ")");

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

            long minE = Long.MAX_VALUE, maxE = Long.MIN_VALUE;
            long minI = Long.MAX_VALUE, maxI = Long.MIN_VALUE;
            for (long[] r : f) {
                minE = Math.min(minE, r[1]); maxE = Math.max(maxE, r[1]);
                minI = Math.min(minI, r[2]); maxI = Math.max(maxI, r[2]);
            }
            log(String.format(Locale.US, "Exposure NYATA: %.3f - %.3f ms (1/%d - 1/%d s)",
                    minE / 1e6, maxE / 1e6,
                    maxE > 0 ? 1_000_000_000L / maxE : 0, minE > 0 ? 1_000_000_000L / minE : 0));
            log("ISO NYATA: " + minI + " - " + maxI);
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
