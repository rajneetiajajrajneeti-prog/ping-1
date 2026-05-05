package com.mdmagent.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class MdmForegroundService extends Service {

    static final String CHANNEL_ID = "mdm_monitoring";
    static final int NOTIFICATION_ID = 101;

    public static MdmForegroundService instance = null;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        applyForeground(false);
        return START_STICKY;
    }

    // Call this from NativeSocketPlugin when screen capture starts/stops
    public void updateForegroundType(boolean withScreenCapture) {
        applyForeground(withScreenCapture);
    }

    private void applyForeground(boolean withScreenCapture) {
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (hasPermission(Manifest.permission.CAMERA)) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            if (withScreenCapture) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
            startForeground(NOTIFICATION_ID, notif, type);
        } else {
            startForeground(NOTIFICATION_ID, notif);
        }
    }

    private boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "MDM Agent", NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("MDM Agent is running");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
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
