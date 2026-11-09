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
    private static final long BOUND_FRESH_MS = 120000L;

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

    public static boolean boundRecently(Context ctx) {
        try {
            long t = ctx.getSharedPreferences("rk", 0).getLong("bind", 0L);
            return t > 0L && System.currentTimeMillis() - t < BOUND_FRESH_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void heal(Context ctx, String tag) {
        final Context app = ctx.getApplicationContext();
        try {
            String pkg = app.getPackageName();
            final String self = pkg + "/" + ResolverKillerService.class.getName();
            final String cur = Settings.Secure.getString(app.getContentResolver(), KEY);
            if (cur == null || !cur.contains(self)) {
                ensure(app, tag + " heal");
                return;
            }
            if (boundRecently(app)) {
                log(app, tag + " heal skipped bound");
                return;
            }
            final String without = strip(cur, self);
            Settings.Secure.putString(app.getContentResolver(), KEY, without);
            log(app, tag + " jitter off " + without);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        Settings.Secure.putString(app.getContentResolver(), KEY, cur);
                        log(app, "jitter restore");
                    } catch (Throwable t) {
                        log(app, "jitter restore fail " + t);
                    }
                }
            }, 1200L);
        } catch (Throwable t) {
            log(app, tag + " heal fail " + t);
        }
    }

    private static String strip(String cur, String self) {
        String without = cur.replace(self, "");
        while (without.contains("::")) {
            without = without.replace("::", ":");
        }
        if (without.startsWith(":")) without = without.substring(1);
        if (without.endsWith(":")) without = without.substring(0, without.length() - 1);
        return without;
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
        ensure(app, "boot");
        scheduleJob(app);
        int[] delays = {5000, 20000, 60000, 150000};
        final Handler h = new Handler(Looper.getMainLooper());
        for (int i = 0; i < delays.length; i++) {
            final int d = delays[i];
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    heal(app, "boot+" + d);
                }
            }, d);
        }
    }
}
