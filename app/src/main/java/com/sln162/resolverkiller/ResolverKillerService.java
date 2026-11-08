package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Resolver Killer
 *
 * 只写 wide（无 DEFAULT）时，HOME 会弹出系统桌面选择框（极米 ResolverActivity）。
 * 本服务的做法：在 HOME 按下时自己把 Aerial 拉到前台，并吞掉该按键，
 * 使系统不再启动桌面解析流程，选择框因而不会出现。
 *
 * 兜底：若仍然收到 ResolverActivity 的窗口变化事件，再拉一次 Aerial。
 *
 * 取证日志写在应用私有目录 files/rk.log，可用 adb run-as 读取。
 */
public class ResolverKillerService extends AccessibilityService {

    private static final String TARGET_CLASS_KEY = "ResolverActivity";
    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_ACT = "app.orchard.tv.MainActivity";

    private static final long COOLDOWN_MS = 300L;

    private long lastEventLaunch = 0L;
    private long lastKeyLaunch = 0L;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) {
            return false;
        }
        trace("key HOME action=" + event.getAction());
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            if (now - lastKeyLaunch > COOLDOWN_MS) {
                lastKeyLaunch = now;
                trace("ACTION key home -> launch");
                launchAerial();
            }
        }
        // 吞掉 HOME，避免系统启动桌面解析流程
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence clsCs = event.getClassName();
        String cls = clsCs == null ? "?" : clsCs.toString();
        trace("evt " + event.getEventType() + " " + cls);

        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        if (!cls.contains(TARGET_CLASS_KEY)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEventLaunch > COOLDOWN_MS) {
            lastEventLaunch = now;
            trace("ACTION resolver -> launch");
            launchAerial();
        }
    }

    private void launchAerial() {
        try {
            Intent intent = new Intent();
            intent.setClassName(AERIAL_PKG, AERIAL_ACT);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            trace("start requested");
        } catch (Exception e) {
            trace("start failed " + e);
        }
    }

    private void trace(String s) {
        try {
            File f = new File(getFilesDir(), "rk.log");
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
