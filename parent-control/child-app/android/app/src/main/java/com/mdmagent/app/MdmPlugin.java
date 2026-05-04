package com.mdmagent.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.CallLog;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "MdmPlugin",
    permissions = {
        @Permission(alias = "callLog", strings = {
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        }),
        @Permission(alias = "sms", strings = {
            Manifest.permission.READ_SMS
        })
    }
)
public class MdmPlugin extends Plugin {

    // ── Device Info ───────────────────────────────────────────────

    @PluginMethod
    public void getDeviceInfo(PluginCall call) {
        JSObject result = new JSObject();
        result.put("model", Build.MODEL);
        result.put("manufacturer", Build.MANUFACTURER);
        result.put("androidVersion", Build.VERSION.RELEASE);
        result.put("sdkInt", Build.VERSION.SDK_INT);
        result.put("androidId", Settings.Secure.getString(
            getContext().getContentResolver(), Settings.Secure.ANDROID_ID));
        call.resolve(result);
    }

    // ── Battery ───────────────────────────────────────────────────

    @PluginMethod
    public void getBattery(PluginCall call) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent bat = getContext().registerReceiver(null, ifilter);
        int level  = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale  = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        int status = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
        int pct    = (scale > 0) ? (int)(level * 100f / scale) : -1;
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL;
        JSObject result = new JSObject();
        result.put("level", pct);
        result.put("charging", charging);
        call.resolve(result);
    }

    // ── Call Logs ─────────────────────────────────────────────────

    @PluginMethod
    public void getCallLogs(PluginCall call) {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            requestPermissionForAlias("callLog", call, "callLogCallback");
            return;
        }
        fetchCallLogs(call);
    }

    @PermissionCallback
    private void callLogCallback(PluginCall call) {
        if (hasPermission(Manifest.permission.READ_CALL_LOG)) fetchCallLogs(call);
        else call.reject("Call log permission denied");
    }

    private void fetchCallLogs(PluginCall call) {
        JSArray logs = new JSArray();
        ContentResolver cr = getContext().getContentResolver();
        String[] proj = {
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DURATION, CallLog.Calls.DATE, CallLog.Calls.TYPE
        };
        try (Cursor c = cr.query(CallLog.Calls.CONTENT_URI, proj, null, null,
                CallLog.Calls.DATE + " DESC")) {
            int count = 0;
            while (c != null && c.moveToNext() && count++ < 50) {
                JSObject e = new JSObject();
                e.put("number",   c.getString(0));
                e.put("name",     c.getString(1));
                e.put("duration", c.getLong(2));
                e.put("date",     c.getLong(3));
                int t = c.getInt(4);
                e.put("type", t == CallLog.Calls.INCOMING_TYPE ? "incoming" :
                               t == CallLog.Calls.OUTGOING_TYPE ? "outgoing" : "missed");
                logs.put(e);
            }
        } catch (Exception ex) {
            call.reject("Error reading call logs: " + ex.getMessage());
            return;
        }
        JSObject res = new JSObject();
        res.put("logs", logs);
        call.resolve(res);
    }

    // ── SMS ───────────────────────────────────────────────────────

    @PluginMethod
    public void getSMS(PluginCall call) {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            requestPermissionForAlias("sms", call, "smsCallback");
            return;
        }
        fetchSMS(call);
    }

    @PermissionCallback
    private void smsCallback(PluginCall call) {
        if (hasPermission(Manifest.permission.READ_SMS)) fetchSMS(call);
        else call.reject("SMS permission denied");
    }

    private void fetchSMS(PluginCall call) {
        JSArray messages = new JSArray();
        ContentResolver cr = getContext().getContentResolver();
        String[] proj = {"address", "body", "date", "type"};
        try (Cursor c = cr.query(Uri.parse("content://sms/"), proj, null, null, "date DESC")) {
            int count = 0;
            while (c != null && c.moveToNext() && count++ < 50) {
                JSObject m = new JSObject();
                m.put("address", c.getString(0));
                m.put("body",    c.getString(1));
                m.put("date",    c.getLong(2));
                m.put("type",    c.getInt(3) == 1 ? "inbox" : "sent");
                messages.put(m);
            }
        } catch (Exception ex) {
            call.reject("Error reading SMS: " + ex.getMessage());
            return;
        }
        JSObject res = new JSObject();
        res.put("messages", messages);
        call.resolve(res);
    }
}
