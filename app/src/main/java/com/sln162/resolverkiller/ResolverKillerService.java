package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 只做一件事：听到极米的桌面选择框（ResolverActivity）出现，就按返回关掉它，
 * 然后立刻把 Aerial 拉到前台。不读 UI 树、不写任何记录、不影响别的窗口。
 *
 * 适用前提：设备处于“只写 wide、不写 DEFAULT”的桌面状态，
 * 按 Home 键时系统会弹 ResolverActivity。
 *
 * 如果换桌面 App 或换品牌固件，改下面三个常量即可：
 *   TARGET_PKG  —— 弹出选择框的包（极米为 com.xgimi.xgimiframeworkui）
 *   AERIAL_PKG / AERIAL_ACT —— 想拉起的桌面 App 组件
 */
public class ResolverKillerService extends AccessibilityService {

    private static final String TAG = "ResolverKiller";

    /** 选择框所在包（实测：com.xgimi.xgimiframeworkui/.resolver.ResolverActivity） */
    private static final String TARGET_PKG = "com.xgimi.xgimiframeworkui";
    private static final String TARGET_CLASS_KEY = "ResolverActivity";

    /** Aerial 的入口 */
    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_ACT = "app.orchard.tv.MainActivity";

    /** 防抖：500ms 内只处理一次，避免重复事件连击 */
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
        if (!pkg.toString().equals(TARGET_PKG)) return;
        if (!cls.toString().contains(TARGET_CLASS_KEY)) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastHandledMs < DEBOUNCE_MS) return;
        lastHandledMs = now;

        Log.i(TAG, "ResolverActivity appeared -> BACK + launch home");

        performGlobalAction(GLOBAL_ACTION_BACK);

        Intent i = new Intent();
        i.setComponent(new ComponentName(AERIAL_PKG, AERIAL_ACT));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            Log.i(TAG, "home launch sent");
        } catch (Exception e) {
            Log.e(TAG, "startActivity failed", e);
        }
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "onInterrupt");
    }
}
