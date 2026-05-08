package com.mdmagent.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility Service — the nuclear auto-start mechanism.
 * Android FORCES all enabled accessibility services to start on every reboot —
 * no OEM (Vivo/Xiaomi/Samsung) can block this without breaking the OS.
 *
 * Also runs a 30s service-health loop: if MdmForegroundService is dead, restarts it.
 */
public class MdmAccessibilityService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable serviceHealthCheck = new Runnable() {
        @Override public void run() {
            // If foreground service was killed by OEM, restart it immediately
            if (MdmForegroundService.instance == null) {
                try {
                    startForegroundService(new Intent(MdmAccessibilityService.this, MdmForegroundService.class));
                } catch (Exception ignored) {}
                HeartbeatReceiver.schedule(MdmAccessibilityService.this);
            }
            handler.postDelayed(this, 30_000);
        }
    };

    @Override
    public void onServiceConnected() {
        // Fires on every boot — start main service immediately
        try {
            startForegroundService(new Intent(this, MdmForegroundService.class));
        } catch (Exception ignored) {}
        HeartbeatReceiver.schedule(this);
        // Start health check loop — keeps service alive even if OEM kills it
        handler.postDelayed(serviceHealthCheck, 30_000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(serviceHealthCheck);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}
