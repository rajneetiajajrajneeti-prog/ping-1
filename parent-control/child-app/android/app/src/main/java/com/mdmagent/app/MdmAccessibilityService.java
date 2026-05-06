package com.mdmagent.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility Service — the most OEM-proof persistence mechanism on Android.
 * Android restarts ALL enabled accessibility services automatically after every reboot,
 * bypassing Vivo/Xiaomi/Samsung battery restrictions entirely.
 *
 * User enables it ONCE via Settings → Accessibility → MDM Agent → ON
 * After that: works forever, no app opening needed, survives all restarts.
 */
public class MdmAccessibilityService extends AccessibilityService {

    @Override
    public void onServiceConnected() {
        // Fires automatically on every boot/restart — start main service + heartbeat chain
        try {
            startForegroundService(new Intent(this, MdmForegroundService.class));
        } catch (Exception ignored) {}
        HeartbeatReceiver.schedule(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used — we only need the lifecycle callbacks
    }

    @Override
    public void onInterrupt() {}
}
