package com.sln162.aerialhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootGuardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BootA11yService.logLine(context, "boot receiver: " + intent.getAction());
        // "这次开机已经拉过一次"的记号在新一次开机时必须作废，否则会把该救的那次当成"刚拉过"。
        try {
            context.createDeviceProtectedStorageContext()
                    .getSharedPreferences("guard", Context.MODE_PRIVATE)
                    .edit().remove("boot_pull_wall").remove("boot_pull_at").apply();
        } catch (Throwable t) {
            BootA11yService.logLine(context, "clear boot_pull_at failed: " + t);
        }
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException ignored) {
                }
                BootA11yService.ensureSelf(app, true);
                pr.finish();
            }
        }, "bootrescue-ensure").start();
    }
}
