package com.mdmagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
            case Intent.ACTION_USER_PRESENT:  // fires on screen unlock — works even when MIUI blocks BOOT_COMPLETED
                context.startForegroundService(new Intent(context, MdmForegroundService.class));
                HeartbeatReceiver.schedule(context);
                break;

            case Intent.ACTION_SHUTDOWN:
                // Gracefully close WebSocket so server marks device offline instantly
                if (MdmForegroundService.instance != null) {
                    try { MdmForegroundService.instance.notifyShutdown(); } catch (Exception ignored) {}
                }
                break;
        }
    }
}
