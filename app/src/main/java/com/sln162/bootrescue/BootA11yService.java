package com.sln162.bootrescue;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BootA11yService extends AccessibilityService {
    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_CLS = "app.orchard.tv.MainActivity";
    private static final String RESOLVER_PKG = "com.xgimi.xgimiframeworkui";
    private static final long WINDOW_MS = 180000L;
    private static final long CHECK_MS = 2000L;
    // 开机动画服务。stopped 说明系统已经把开机画面收掉，该显示界面了，跟过了多少秒无关
    private static final String BOOTANIM_PROP = "init.svc.bootanim";
    private static final long ENSURE_DEBOUNCE_MS = 30000L;

    private static long LAST_ENSURE = 0L;
    private static volatile boolean WRITING = false;

    private Handler handler;
    private long startedAt;
    private int attempts;
    private ContentObserver observer;

    private final Runnable ensureRun = new Runnable() {
        @Override
        public void run() {
            ensureSelf(BootA11yService.this, true);
            handler.postDelayed(ensureRun2, 4000);
        }
    };

    private final Runnable ensureRun2 = new Runnable() {
        @Override
        public void run() {
            ensureSelf(BootA11yService.this, true);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        startedAt = SystemClock.elapsedRealtime();
        attempts = 0;
        log("bound, uptime=" + startedAt + "ms");
        ensureSelf(this, true);
        watchSettings();
        handler.postDelayed(tick, CHECK_MS);
    }

    private void watchSettings() {
        try {
            ContentResolver cr = getContentResolver();
            observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    if (WRITING) {
                        return;
                    }
                    log("a11y list changed");
                    handler.removeCallbacks(ensureRun);
                    handler.removeCallbacks(ensureRun2);
                    handler.postDelayed(ensureRun, 1500);
                }
            };
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false, observer);
        } catch (Throwable t) {
            log("watch failed: " + t);
        }
    }

    static void ensureSelf(Context context, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && LAST_ENSURE != 0L && now - LAST_ENSURE < ENSURE_DEBOUNCE_MS) {
            return;
        }
        LAST_ENSURE = now;
        try {
            ComponentName me = new ComponentName(context.getPackageName(),
                    BootA11yService.class.getName());
            String flat = me.flattenToString();
            String cur = readList(context);
            logLine(context, "ensure cur=" + (cur == null ? "null" : cur));
            if (cur != null && cur.contains(flat)) {
                return;
            }
            String next = (cur == null || cur.isEmpty()) ? flat : cur + ":" + flat;
            WRITING = true;
            boolean ok;
            try {
                ok = writeList(context, next);
                if (!ok) {
                    ok = writeList(context.createDeviceProtectedStorageContext(), next);
                }
            } finally {
                WRITING = false;
            }
            if (ok) {
                logLine(context, "restored self into a11y list");
            }
            try {
                Settings.Secure.putInt(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            WRITING = false;
            logLine(context, "ensureSelf failed: " + t);
        }
    }

    static String readList(Context context) {
        try {
            String v = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (v != null) {
                return v;
            }
        } catch (Throwable ignored) {
        }
        try {
            String v = Settings.Secure.getString(
                    context.createDeviceProtectedStorageContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (v != null) {
                return v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean writeList(Context context, String value) {
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
            return true;
        } catch (Throwable t) {
            logLine(context, "write failed: " + t);
            return false;
        }
    }

    private int nullStreak = 0;
    // Aerial 只要到过一次前台，就说明桌面自己能起来，这次开机不用管了
    private boolean everSawAerial = false;
    // 开机动画结束之后，连续几次看到"该有画面却是空的"
    private int stall = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            if (now - startedAt > WINDOW_MS) {
                log("window closed");
                return;
            }
            // 只有开机 10 分钟内才救。日常重新启用服务时 uptime 早就超了，一律不动
            if (now > 600000L) {
                log("uptime " + now + " too long, stand down");
                return;
            }
            attempts++;
            String front = frontPackage();
            if (front != null && AERIAL_PKG.equals(front)) {
                everSawAerial = true;
                log("aerial in front, done");
                return;
            }
            if (everSawAerial) {
                return;
            }
            // 系统还在开机（白屏、动画、自检、认证）就不动。这一段多长跟机型快慢有关，不看秒数
            if (!bootFinished()) {
                log("still booting (bootanim=" + prop(BOOTANIM_PROP) + "), waiting");
                handler.postDelayed(this, CHECK_MS);
                return;
            }
            // 开机动画已经收掉了，还看不到界面：这种情况才算"该出来没出来"
            boolean stuck = front == null || isFallbackOrResolver(front);
            if (!stuck) {
                stall = 0;
                log("boot finished, front=" + front + ", leave alone");
                handler.postDelayed(this, CHECK_MS);
                return;
            }
            if (hasAnyWindow()) {
                stall = 0;
                log("boot finished, front=" + front + " but window list not empty, keep waiting");
                handler.postDelayed(this, CHECK_MS);
                return;
            }
            stall++;
            log("boot finished but no usable window (" + stall + "/2, front=" + front + ")");
            if (stall >= 2) {
                log("boot finished and still nothing, aerial never in front, pulling aerial");
                pull();
                return;
            }
            handler.postDelayed(this, CHECK_MS);
        }
    };

    /** 系统开机流程走完了没有：开机动画收掉，或者系统自己置了 boot_completed。跟时长无关。 */
    private boolean bootFinished() {
        if ("stopped".equals(prop(BOOTANIM_PROP))) {
            return true;
        }
        return "1".equals(prop("sys.boot_completed"));
    }

    private String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = c.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? "" : v.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean isFallbackOrResolver(String front) {
        return front.contains("newsettings") || front.contains("Resolver");
    }

    private String frontPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return null;
            }
            String pkg = root.getPackageName() == null ? null : root.getPackageName().toString();
            try {
                root.recycle();
            } catch (Throwable ignored) {
            }
            return pkg;
        } catch (Throwable t) {
            log("frontPackage failed: " + t);
            return null;
        }
    }

    private boolean hasAnyWindow() {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            return ws != null && !ws.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private void pull() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(AERIAL_PKG, AERIAL_CLS));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            // 画面救回来之后，弹一次提醒页。只排一次，否则黑屏那几秒会被重复排多次、反复闪
            if (!tipPending) {
                tipPending = true;
                handler.postDelayed(tipRun, 3000);
            }
        } catch (Throwable t) {
            log("startActivity failed: " + t);
        }
    }

    private boolean tipPending = false;

    private final Runnable tipRun = new Runnable() {
        @Override
        public void run() {
            try {
                Intent i = new Intent();
                i.setClassName(getPackageName(), "com.sln162.bootrescue.MainActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra("boot_tip", true);
                startActivity(i);
                log("boot tip shown");
            } catch (Throwable t) {
                log("tip failed: " + t);
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private void log(String msg) {
        logLine(this, msg);
    }

    static void logLine(Context context, String msg) {
        try {
            Context de = context.createDeviceProtectedStorageContext();
            File dir = de.getFilesDir();
            if (dir == null) {
                return;
            }
            dir.mkdirs();
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new Date()) + " " + msg + "\n";
            FileOutputStream out = new FileOutputStream(new File(dir, "rescue.log"), true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
