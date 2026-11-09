package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
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
    private static final long COVER_MAX_MS = 2500L;
    private static final long COVER_TAIL_MS = 150L;
    private static final int COVER_COLOR = 0xFF0C0C0C;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastLaunch = 0L;
    private long homeDownTime = -1L;
    private boolean longPressHandled = false;
    private boolean resolverSeen = false;
    private WindowManager wm;
    private View cover;

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

    private final Runnable coverOff = new Runnable() {
        @Override public void run() {
            hideCover("off");
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        trace("service connected");
        BootReceiver.ensure(this, "connect");
        BootReceiver.scheduleJob(this);
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        } catch (Throwable t) {
            trace("wm fail " + t);
        }
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
            resolverSeen = false;
            trace("key HOME down -> launch now");
            showCover();
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
        if (cls.contains(AERIAL_ACT) && resolverSeen && cover != null) {
            handler.removeCallbacks(coverOff);
            handler.postDelayed(coverOff, COVER_TAIL_MS);
            trace("aerial back -> cover tail");
            return;
        }
        if (!cls.contains(TAG_CLASS)) return;
        resolverSeen = true;
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

    private void showCover() {
        if (cover != null) {
            handler.removeCallbacks(coverOff);
            handler.postDelayed(coverOff, COVER_MAX_MS);
            return;
        }
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            View v = new View(this);
            v.setBackgroundColor(COVER_COLOR);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            wm.addView(v, lp);
            cover = v;
            trace("cover on");
        } catch (Throwable t) {
            cover = null;
            trace("cover fail " + t);
        }
        handler.removeCallbacks(coverOff);
        handler.postDelayed(coverOff, COVER_MAX_MS);
    }

    private void hideCover(String why) {
        if (cover == null) return;
        try {
            wm.removeView(cover);
            trace("cover off " + why);
        } catch (Throwable t) {
            trace("cover off fail " + t);
        }
        cover = null;
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

    @Override
    public void onDestroy() {
        hideCover("destroy");
        super.onDestroy();
    }

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
