package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
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

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            homeDownTime = event.getEventTime();
            trace("key HOME down");
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            long dur = homeDownTime < 0 ? 0 : event.getEventTime() - homeDownTime;
            homeDownTime = -1L;
            trace("key HOME up dur=" + dur);
            if (dur < LONG_PRESS_MS) {
                trace("short press -> launch");
                launchAerial();
            } else {
                trace("long press -> skip");
            }
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
