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
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                // APK updated — reset setup wizard so new permissions are re-requested
                context.getSharedPreferences("mdm_setup", Context.MODE_PRIVATE)
                    .edit().remove("setup_done").apply();
                // fall through to start service
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
            case "com.vivo.action.BOOT_COMPLETED":
            case Intent.ACTION_USER_PRESENT:
            case Intent.ACTION_SCREEN_ON:
                try {
                    context.startForegroundService(new Intent(context, MdmForegroundService.class));
                } catch (Exception ignored) {}
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
