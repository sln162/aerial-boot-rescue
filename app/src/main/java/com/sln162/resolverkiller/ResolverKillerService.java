package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import java.io.File;
import java.io.FileWriter;

public class ResolverKillerService extends AccessibilityService {

    private static final String TAG_CLASS = "ResolverActivity";
    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_ACT = "app.orchard.tv.MainActivity";
    private static final long COOLDOWN_MS = 600L;
    private static final long BACK_DELAY_MS = 0L;
    private static final long LAUNCH_DELAY_MS = 120L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastFallback = 0L;

    private final ContentObserver healObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            trace("settings changed -> heal");
            BootReceiver.heal(ResolverKillerService.this, "obs");
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        markBound();
        trace("service connected");
        BootReceiver.ensure(this, "connect");
        BootReceiver.scheduleJob(this);
        try {
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor("enabled_accessibility_services"), false, healObserver);
            trace("observer registered");
        } catch (Throwable t) {
            trace("observer fail " + t);
        }
    }

    private void markBound() {
        try {
            getSharedPreferences("rk", 0).edit()
                    .putLong("bind", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence clsSeq = event.getClassName();
        String cls = clsSeq == null ? "" : clsSeq.toString();
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!cls.contains(TAG_CLASS)) return;
        long now = System.currentTimeMillis();
        if (now - lastFallback < COOLDOWN_MS) {
            trace("resolver skip cooldown");
            return;
        }
        lastFallback = now;
        trace("resolver -> back + launch");
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }, BACK_DELAY_MS);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                launchAerial();
            }
        }, LAUNCH_DELAY_MS);
    }

    private void launchAerial() {
        Intent i = new Intent();
        i.setClassName(AERIAL_PKG, AERIAL_ACT);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            trace("start requested");
        } catch (Exception e) {
            trace("start failed " + e);
        }
    }

    @Override
    public void onInterrupt() {}

    private void trace(String s) {
        try {
            File f = new File(getFilesDir(), "rk.log");
            if (f.length() > 100000) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + s + "\n");
            w.close();
        } catch (Exception ignored) {}
    }
}
