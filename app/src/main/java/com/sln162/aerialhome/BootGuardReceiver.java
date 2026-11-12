package com.sln162.aerialhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootGuardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BootA11yService.logLine(context, "boot receiver: " + intent.getAction());
        // "这次开机已经拉过一次"的记号存的是 elapsedRealtime，重启后基准归零；
        // 留着上一次开机的数值，比较出来就是负数，看起来像"刚拉过"，结果该救的时候不救、停在黑屏。
        try {
            context.createDeviceProtectedStorageContext()
                    .getSharedPreferences("guard", Context.MODE_PRIVATE)
                    .edit().remove("boot_pull_at").apply();
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
