package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.io.File;
import java.io.FileWriter;

public class ResolverKillerService extends AccessibilityService {

    private static final String TAG_CLASS = "ResolverActivity";
    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_ACT = "app.orchard.tv.MainActivity";
    private static final long COOLDOWN_MS = 300L;
    private static final long BACK_DELAY_MS = 120L;
    private static final long LONG_PRESS_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastLaunch = 0L;
    private long homeDownTime = -1L;
    private boolean longPressHandled = false;

    private final ContentObserver healObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            trace("settings changed -> heal");
            BootReceiver.ensure(ResolverKillerService.this, "obs");
        }
    };

    private final Runnable longPressCheck = new Runnable() {
        @Override public void run() {
            if (homeDownTime < 0) return;
            longPressHandled = true;
            trace("long press -> recents");
            performGlobalAction(GLOBAL_ACTION_RECENTS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        trace("service connected");
        BootReceiver.ensure(this, "connect");
        try {
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor("enabled_accessibility_services"), false, healObserver);
            trace("observer registered");
        } catch (Throwable t) {
            trace("observer fail " + t);
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            homeDownTime = event.getEventTime();
            longPressHandled = false;
            trace("key HOME down -> launch now");
            launchAerial();
            handler.postDelayed(longPressCheck, LONG_PRESS_MS);
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            long dur = homeDownTime < 0 ? 0 : event.getEventTime() - homeDownTime;
            homeDownTime = -1L;
            handler.removeCallbacks(longPressCheck);
            trace("key HOME up dur=" + dur + (longPressHandled ? " recents=1" : ""));
            return false;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence clsSeq = event.getClassName();
        String cls = clsSeq == null ? "" : clsSeq.toString();
        trace("evt " + event.getEventType() + " " + cls);
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!cls.contains(TAG_CLASS)) return;
        if (longPressHandled) return;
        long now = System.currentTimeMillis();
        if (now - lastLaunch < COOLDOWN_MS) return;
        trace("resolver -> back + launch");
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                performGlobalAction(GLOBAL_ACTION_BACK);
                trace("back sent");
            }
        }, BACK_DELAY_MS);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                launchAerial();
            }
        }, BACK_DELAY_MS + 100L);
    }

    private void launchAerial() {
        long now = System.currentTimeMillis();
        if (now - lastLaunch < COOLDOWN_MS) return;
        lastLaunch = now;
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
            if (f.length() > 200000) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + s + "\n");
            w.close();
        } catch (Exception ignored) {}
    }
}
