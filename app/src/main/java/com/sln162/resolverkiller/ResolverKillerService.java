package com.sln162.resolverkiller;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Resolver Killer
 *
 * 只写 wide（无 DEFAULT）时，HOME 会弹出极米 ResolverActivity（桌面选择框）。
 * 处理方式：
 *  1) HOME 按下时立刻把 Aerial 拉到前台；
 *  2) 选择框出现时立刻发 BACK 把它关掉，再拉一次 Aerial。
 * 这样把"弹框挂着等"的那几秒砍掉，只剩 Aerial 自己重绘的时间。
 *
 * 取证日志写在应用私有目录 files/rk.log，可用 adb run-as 读取。
 */
public class ResolverKillerService extends AccessibilityService {

    private static final String TARGET_CLASS_KEY = "ResolverActivity";
    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_ACT = "app.orchard.tv.MainActivity";

    private static final long COOLDOWN_MS = 300L;
    private static final long BACK_DELAY_MS = 120L;

    private long lastEventLaunch = 0L;
    private long lastKeyLaunch = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());

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
        return false;
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
        if (now - lastEventLaunch <= COOLDOWN_MS) {
            return;
        }
        lastEventLaunch = now;
        trace("ACTION resolver -> back + launch");
        // 等窗口真正拿到焦点再发返回，否则可能作用到下层应用
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(GLOBAL_ACTION_BACK);
                trace("back sent");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        launchAerial();
                    }
                }, 100L);
            }
        }, BACK_DELAY_MS);
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
