package com.sln162.resolverkiller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.File;
import java.io.FileWriter;

public class BootReceiver extends BroadcastReceiver {

    private static final String KEY = "enabled_accessibility_services";

    public static void ensure(Context ctx, String tag) {
        try {
            String pkg = ctx.getPackageName();
            String self = pkg + "/" + ResolverKillerService.class.getName();
            String cur = Settings.Secure.getString(ctx.getContentResolver(), KEY);
            if (cur != null && cur.contains(pkg + "/")) {
                log(ctx, tag + " present");
                return;
            }
            String next = (cur == null || cur.trim().length() == 0) ? self : cur + ":" + self;
            Settings.Secure.putString(ctx.getContentResolver(), KEY, next);
            log(ctx, tag + " wrote " + next);
        } catch (Throwable t) {
            log(ctx, tag + " fail " + t);
        }
    }

    private static void log(Context ctx, String s) {
        try {
            File f = new File(ctx.getFilesDir(), "rk.log");
            if (f.length() > 200000) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        log(app, "boot received");
        int[] delays = {5000, 20000, 60000, 150000};
        final Handler h = new Handler(Looper.getMainLooper());
        for (int i = 0; i < delays.length; i++) {
            final int d = delays[i];
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    ensure(app, "boot+" + d);
                }
            }, d);
        }
    }
}
