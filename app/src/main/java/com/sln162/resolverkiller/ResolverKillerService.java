package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.accessibilityservice.AccessibilityService.GlobalAction;

/**
 * 听到极米的桌面选择框（ResolverActivity）出现，先按返回关掉它，
 * 再把 Aerial 带回前台。Aerial 进程还在就用 REORDER_TO_FRONT 直接提上来，
 * 不在才正常创建。不读 UI 树、不写任何首选记录。
 */
public class ResolverKillerService extends AccessibilityService {

    private static final String TAG = "ResolverKiller";

    private static final String TARGET_PKG = "com.xgimi.xgimiframeworkui";
    private static final String TARGET_CLASS_KEY = "ResolverActivity";

    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_ACT = "app.orchard.tv.MainActivity";

    private static final long DEBOUNCE_MS = 500L;
    private long lastHandledMs = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        if (!TARGET_PKG.equals(pkg.toString())) return;
        if (!cls.toString().contains(TARGET_CLASS_KEY)) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastHandledMs < DEBOUNCE_MS) return;
        lastHandledMs = now;

        // 第一步：把选择框关掉，别让它占着前台拦焦点
        performGlobalAction(GLOBAL_ACTION_BACK);

        // 第二步：把 Aerial 带回来
        boolean running = isAerialRunning();
        Intent i = new Intent();
        i.setComponent(new ComponentName(AERIAL_PKG, AERIAL_ACT));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (running) {
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        }
        try {
            startActivity(i);
            Log.i(TAG, "back + home brought to front, reorder=" + running);
        } catch (Exception e) {
            Log.e(TAG, "startActivity failed", e);
        }
    }

    private boolean isAerialRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            java.util.List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return false;
            for (ActivityManager.RunningAppProcessInfo p : ps) {
                if (AERIAL_PKG.equals(p.processName)) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "check running failed", e);
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "onInterrupt");
    }
}
