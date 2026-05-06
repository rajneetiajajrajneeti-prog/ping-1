package com.mdmagent.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * Fires every 60 seconds via AlarmManager (works even in Doze mode).
 * Ensures the foreground service and WebSocket connection stay alive
 * even on OEMs with aggressive battery management (Xiaomi, Samsung, etc.)
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    static final String ACTION      = "com.mdmagent.app.HEARTBEAT";
    static final long   INTERVAL_MS = 60_000; // 60 seconds

    @Override
    public void onReceive(Context context, Intent intent) {
        // Start (or restart) the service — it will reconnect WebSocket if needed
        try {
            context.startForegroundService(new Intent(context, MdmForegroundService.class));
        } catch (Exception ignored) {}
        // Chain: schedule next heartbeat immediately
        schedule(context);
    }

    /** Call once on app start / boot. Starts the alarm chain. */
    static void schedule(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = getPendingIntent(context);
            long trigger = SystemClock.elapsedRealtime() + INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // setExactAndAllowWhileIdle fires even during Doze mode
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            }
        } catch (Exception ignored) {}
    }

    /** Call only when truly disconnecting (user signed out). */
    static void cancel(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(getPendingIntent(context));
        } catch (Exception ignored) {}
    }

    private static PendingIntent getPendingIntent(Context context) {
        Intent i = new Intent(context, HeartbeatReceiver.class);
        i.setAction(ACTION);
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
