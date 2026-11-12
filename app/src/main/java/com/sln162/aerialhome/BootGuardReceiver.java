package com.sln162.aerialhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootGuardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BootA11yService.logLine(context, "boot receiver: " + intent.getAction());
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
