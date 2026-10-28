package com.mylifeai.phonebridge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class SyncJob extends JobService {
    private static final int PERIODIC = 8031, SOON = 8032;
    public static void schedule(Context c) {
        if (!BridgeStore.enabled(c)) return;
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(PERIODIC, new ComponentName(c, SyncJob.class))
            .setPeriodic(15 * 60 * 1000L).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }
    public static void requestSoon(Context c) {
        if (!BridgeStore.enabled(c) || BridgeStore.prefs(c).getString("target_uri", "").isEmpty()) return;
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(SOON) == null) scheduler.schedule(new JobInfo.Builder(SOON, new ComponentName(c, SyncJob.class))
            .setMinimumLatency(5000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }
    public static void stop(Context c) {
        JobScheduler s = c.getSystemService(JobScheduler.class); s.cancel(PERIODIC); s.cancel(SOON);
    }
    @Override public boolean onStartJob(JobParameters params) {
        PhoneSync.IO.execute(() -> {
            if (BridgeStore.enabled(this)) PhoneSync.publish(this);
            jobFinished(params, false);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return BridgeStore.enabled(this); }
}
