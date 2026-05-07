package com.mdmagent.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

public class HeartbeatReceiver extends BroadcastReceiver {

    static final String ACTION      = "com.mdmagent.app.HEARTBEAT";
    static final long   INTERVAL_MS = 180_000; // 3 minutes

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.startForegroundService(new Intent(context, MdmForegroundService.class));
        } catch (Exception ignored) {}
        schedule(context);
    }

    static void schedule(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = getPendingIntent(context);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // setAlarmClock = OEM-proof, same priority as clock app alarms
                // Vivo/Xiaomi/Samsung CANNOT block this even in deep sleep
                am.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + INTERVAL_MS, null),
                    pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS, pi);
            }
        } catch (Exception ignored) {}
    }

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
