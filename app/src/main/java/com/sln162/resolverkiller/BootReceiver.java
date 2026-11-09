package com.sln162.resolverkiller;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.File;
import java.io.FileWriter;

public class BootReceiver extends BroadcastReceiver {

    private static final String KEY = "enabled_accessibility_services";
    private static final int JOB_ID = 16201;

    public static void ensure(Context ctx, String tag) {
        try {
            String pkg = ctx.getPackageName();
            String self = pkg + "/" + ResolverKillerService.class.getName();
            String cur = Settings.Secure.getString(ctx.getContentResolver(), KEY);
            if (cur != null && cur.contains(pkg + "/")) {
                return;
            }
            String next = (cur == null || cur.trim().length() == 0) ? self : cur + ":" + self;
            Settings.Secure.putString(ctx.getContentResolver(), KEY, next);
            log(ctx, tag + " wrote " + next);
        } catch (Throwable t) {
            log(ctx, tag + " fail " + t);
        }
    }

    public static void scheduleJob(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(ctx, HealJobService.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true);
            js.schedule(b.build());
            log(ctx, "job scheduled");
        } catch (Throwable t) {
            log(ctx, "job fail " + t);
        }
    }

    private static void log(Context ctx, String s) {
        try {
            File f = new File(ctx.getFilesDir(), "rk.log");
            if (f.length() > 100000) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        log(app, "boot received");
        DeviceOwnerSetup.ensure(app);
        scheduleJob(app);
        int[] delays = {5000, 20000, 60000, 150000};
        final Handler h = new Handler(Looper.getMainLooper());
        for (int i = 0; i < delays.length; i++) {
            final int d = delays[i];
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    DeviceOwnerSetup.ensure(app);
                    ensure(app, "boot+" + d);
                }
            }, d);
        }
    }
}
