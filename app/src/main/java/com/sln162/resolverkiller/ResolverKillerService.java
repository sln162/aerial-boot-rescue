package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Resolver Killer
 *
 * 只写 wide（无 DEFAULT）时，HOME 会弹出系统桌面选择框（极米 ResolverActivity）。
 * 本服务监听该 Activity 出现，立即把 Aerial 桌面拉回，让选择框一闪而过。
 *
 * v4 关键改动（经 adb 实测验证）：
 *  - 不再发送 BACK：实测 BACK 会把系统 Home 解析流程拖到 4.9 秒
 *  - 不再使用 REORDER_TO_FRONT：实测该标志要等系统释放任务栈，多花 3.5 秒
 *  - 只用 FLAG_ACTIVITY_NEW_TASK 直接 start：实测 0.5 秒左右回到 Aerial
 */
public class ResolverKillerService extends AccessibilityService {

    private static final String TAG = "ResolverKiller";

    /** 极米框架 UI 包名，ResolverActivity 所在 */
    private static final String TARGET_PKG = "com.xgimi.xgimiframeworkui";
    /** 类名关键字，兼容可能带后缀的实现 */
    private static final String TARGET_CLASS_KEY = "ResolverActivity";

    /** Aerial 桌面 */
    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_ACT = "app.orchard.tv.MainActivity";

    /** 防抖：避免短时间重复触发 */
    private static final long DEBOUNCE_MS = 500L;

    private long lastActionTime = 0L;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        CharSequence pkgName = event.getPackageName();
        CharSequence clsName = event.getClassName();
        if (pkgName == null || clsName == null) {
            return;
        }
        if (!TARGET_PKG.equals(pkgName.toString())) {
            return;
        }
        if (!clsName.toString().contains(TARGET_CLASS_KEY)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastActionTime < DEBOUNCE_MS) {
            return;
        }
        lastActionTime = now;

        launchAerial();
    }

    private void launchAerial() {
        try {
            Intent intent = new Intent();
            intent.setClassName(AERIAL_PKG, AERIAL_ACT);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.i(TAG, "resolver detected, aerial brought to front (no back, no reorder)");
        } catch (Exception e) {
            Log.e(TAG, "launch aerial failed", e);
        }
    }

    @Override
    public void onInterrupt() {
        // no-op
    }
}
