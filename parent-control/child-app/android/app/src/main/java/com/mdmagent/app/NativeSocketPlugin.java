package com.mdmagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

@CapacitorPlugin(name = "NativeSocket")
public class NativeSocketPlugin extends Plugin {

    static NativeSocketPlugin instance = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void load() {
        super.load();
        instance = this;
    }

    @PluginMethod
    public void connect(PluginCall call) {
        String url      = call.getString("url");
        String deviceId = call.getString("deviceId");
        // Persist credentials so service auto-reconnects after reboot/kill
        getContext().getSharedPreferences(MdmForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(MdmForegroundService.PREF_URL, url)
            .putString(MdmForegroundService.PREF_DEVICE_ID, deviceId)
            .apply();
        attemptConnect(url, deviceId, 0);
        call.resolve();
    }

    private void attemptConnect(String url, String deviceId, int attempt) {
        if (MdmForegroundService.instance != null) {
            MdmForegroundService.instance.connect(url, deviceId);
        } else if (attempt < 10) {
            mainHandler.postDelayed(() -> attemptConnect(url, deviceId, attempt + 1), 400);
        }
    }

    @PluginMethod
    public void socketEmit(PluginCall call) {
        String event = call.getString("event");
        String json  = call.getString("data", "{}");
        if (MdmForegroundService.instance == null) { call.reject("service not running"); return; }
        try {
            MdmForegroundService.instance.socketEmit(event, new JSONObject(json));
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void socketDisconnect(PluginCall call) {
        if (MdmForegroundService.instance != null) MdmForegroundService.instance.disconnect();
        call.resolve();
    }

    // Called by MdmForegroundService to push events into WebView JS
    public void fireJsPublic(String event, String jsonPayload) {
        String payload = (jsonPayload != null) ? jsonPayload : "null";
        String js = "window.dispatchEvent(new CustomEvent('nativesocket',{detail:{event:'"
                + event.replace("'", "\\'") + "',payload:" + payload + "}}))";
        mainHandler.post(() -> {
            try {
                WebView wv = getBridge().getWebView();
                if (wv != null) wv.evaluateJavascript(js, null);
            } catch (Exception ignored) {}
        });
    }
}
