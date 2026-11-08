package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Resolver Killer
 *
 * 只写 wide（无 DEFAULT）时，HOME 会弹出系统桌面选择框（极米 ResolverActivity）。
 * 本服务监听窗口变化，把 Aerial 桌面拉回。
 *
 * 本版本带取证日志：
 * /sdcard/Android/data/com.sln162.resolverkiller/files/rk.log
 */
public class ResolverKillerService extends AccessibilityService {

    private static final String TAG = "ResolverKiller";

    private static final String TARGET_CLASS_KEY = "ResolverActivity";
    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_ACT = "app.orchard.tv.MainActivity";

    private static final long COOLDOWN_MS = 250L;

    private long lastActionTime = 0L;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkgCs = event.getPackageName();
        CharSequence clsCs = event.getClassName();
        String pkg = pkgCs == null ? "?" : pkgCs.toString();
        String cls = clsCs == null ? "?" : clsCs.toString();

        trace("evt type=" + event.getEventType() + " pkg=" + pkg + " cls=" + cls);

        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        if (!cls.contains(TARGET_CLASS_KEY)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastActionTime < COOLDOWN_MS) {
            trace("skip cooldown");
            return;
        }
        lastActionTime = now;
        trace("ACTION resolver detected, launching aerial");
        launchAerial();
    }

    private void launchAerial() {
        try {
            Intent intent = new Intent();
            intent.setClassName(AERIAL_PKG, AERIAL_ACT);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            trace("start requested");
        } catch (Exception e) {
            trace("start failed: " + e);
            Log.e(TAG, "launch aerial failed", e);
        }
    }

    private void trace(String s) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "rk.log");
            if (f.length() > 200000L) {
                f.delete();
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((System.currentTimeMillis() + " " + s + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignore) {
            // ignore
        }
    }

    @Override
    public void onInterrupt() {
        // no-op
    }
}
