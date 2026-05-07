package com.mdmagent.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Persistent job that survives reboots via JobScheduler.setPersisted(true).
 * Android GUARANTEES this runs after every boot — Vivo/Xiaomi/Samsung cannot block it
 * because JobScheduler is a system service (blocking it would break the OS itself).
 * Acts as the nuclear fallback when BOOT_COMPLETED is blocked by the OEM.
 */
public class BootJobService extends JobService {

    static final int JOB_ID = 9001;
    // 15 min is JobScheduler's minimum periodic interval; actual wakeup is within 15–30 min.
    static final long PERIOD_MS = 15 * 60 * 1000L;

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            startForegroundService(new Intent(this, MdmForegroundService.class));
        } catch (Exception ignored) {}
        HeartbeatReceiver.schedule(this);
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if interrupted
    }

    /** Schedule a persistent periodic job — call once from service onCreate(). */
    static void ensureScheduled(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            // Don't duplicate if already pending
            for (JobInfo j : js.getAllPendingJobs()) {
                if (j.getId() == JOB_ID) return;
            }
            ComponentName cn = new ComponentName(ctx, BootJobService.class);
            JobInfo job = new JobInfo.Builder(JOB_ID, cn)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)  // survives reboots — key feature
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();
            js.schedule(job);
        } catch (Exception ignored) {}
    }
}
