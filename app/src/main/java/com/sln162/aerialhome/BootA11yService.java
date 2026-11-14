package com.sln162.aerialhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BootA11yService extends AccessibilityService {
    private static final String AERIAL_PKG = "app.orchard.tv";
    private static final String AERIAL_CLS = "app.orchard.tv.MainActivity";
    private static final long WINDOW_MS = 180000L;
    private static final long CHECK_MS = 2000L;
    /** 两次"救"之间至少隔这么久：太密会自己跟自己抢，太慢就白等 */
    private static final long PULL_COOLDOWN_MS = 5000L;
    /** 一次运行里最多救这么多次，防止目标桌面根本起不来时无限重试 */
    private static final int PULL_CAP = 3;
    // 开机动画服务。stopped 说明系统已经把开机画面收掉，该显示界面了，跟过了多少秒无关
    private static final String BOOTANIM_PROP = "init.svc.bootanim";
    private static final long ENSURE_DEBOUNCE_MS = 30000L;

    private static long LAST_ENSURE = 0L;
    private static volatile boolean WRITING = false;
    /** 上一次打印过的无障碍列表内容；内容没变就不再打 */
    private static String LAST_LIST_LOGGED = null;
    /** 日志落盘用同一把锁：轮转和写入不能交叉，否则一段日志可能被切到两个文件里 */
    private static final Object LOG_LOCK = new Object();
    /** 日志过了这个体积就换到 rescue.log.1，只留一代（出问题时还有上一段可看） */
    private static final long LOG_MAX_BYTES = 512L * 1024L;
    /** 服务真的被系统绑上了没有。界面拿它判断"在不在运行"，比只看无障碍开关可靠 */
    private static volatile boolean BOUND = false;

    static boolean isBound() {
        return BOUND;
    }

    private Handler handler;
    private long startedAt;
    /**
     * 观察者与包变更接收器必须是「全进程唯一」的 static。
     * Android 每次重绑都是 new 一个服务对象，旧对象还活着、它注册在系统上的监听也还在；
     * 用实例变量只摘得掉「自己字段里那个」，旧的照样回调——实测同一次列表变更仍出两条
     * "a11y list changed"。只有静态字段才跨得过「新对象」这道墙。
     * owner 记的是注册它的那个实例：旧对象后来被销毁时，不能把新对象的监听顺手摘掉。
     */
    private static ContentObserver sObserver = null;
    private static int sObserverOwner = 0;
    private static BroadcastReceiver sPacks = null;
    private static int sPacksOwner = 0;

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
        BOUND = true;
        instanceId = ++INSTANCE_SEQ;
        // Handler 每次重绑都会新建一个，removeCallbacks 必须作用在旧的那个上：
        // 先新建再 remove 等于对着空气喊话，旧 Handler 里的 tick/watch 照样跑（实测出现过
        // 两条 tick 并行：stall 数到 5/2、Aerial 已经在前台了还多拉一次）。
        if (handler != null) {
            handler.removeCallbacks(tick);
            handler.removeCallbacks(watch);
        }
        handler = new Handler(Looper.getMainLooper());
        startedAt = SystemClock.elapsedRealtime();
        log("bound, uptime=" + startedAt + "ms");
        // 两个钟是分两次读的，读的先后会差出几毫秒，别显示出负数
        log("clocks: elapsed=" + startedAt + "ms, deepsleep since boot="
                + Math.max(0L, startedAt - SystemClock.uptimeMillis()) + "ms");
        // 同一次开机里服务被重绑（极米改写无障碍列表就会），新对象从零开始，
        // 会把「刚看过 Aerial」当成黑屏再拉一次——极米随即又走一遍 HOME 解析，
        // 用户看到的就是「弹框两次」。这里把同一开机内刚记下的「见过」继承过来。
        if (inheritSeen()) {
            everSawAerial = true;
            log("seen: aerial was already in front this boot, inherited (no extra pull)");
        }
        ensureSelf(this, true);
        watchSettings();
        // 只关心框里那两个包：Aerial 后装、或者被升级之后，缓存的标签要重读
        try {
            // 重绑是新建对象：要摘的是「上一个对象注册的那个」，只能靠静态字段找到它
            if (sPacks != null) {
                try {
                    unregisterReceiver(sPacks);
                } catch (Throwable ignored) {
                }
                sPacks = null;
                sPacksOwner = 0;
            }
            sPacks = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String pkg = intent == null ? null : intent.getData() == null
                            ? null : intent.getData().getSchemeSpecificPart();
                    if (pkg == null || (!AERIAL_PKG.equals(pkg) && !XGIMI_HOME.equals(pkg))) {
                        return;
                    }
                    invalidateLabels(pkg + " " + (intent.getAction() == null ? "" : intent.getAction()));
                }
            };
            IntentFilter packFilter = new IntentFilter();
            packFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            packFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            packFilter.addDataScheme("package");
            registerReceiver(sPacks, packFilter);
            sPacksOwner = instanceId;
        } catch (Throwable t) {
            log("pack receiver failed: " + t);
        }
        // 排新的循环（旧的已经在上面摘掉了）
        handler.postDelayed(tick, CHECK_MS);
        handler.postDelayed(watch, WATCH_MS);
        // 刚绑上就先看一眼：开机那次的弹框如果这会儿还在，立刻点掉
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    AccessibilityNodeInfo root = chooserRootQuiet();
                    if (root != null) {
                        long now = SystemClock.elapsedRealtime();
                        // 记一笔「刚处理过框」：不记的话 4 秒冷却等于没生效，紧接着看门狗
                        // 又起一轮同样的探针去重复点行（第 1、3 次开机都出现过这份重复）。
                        // 只挡 2 秒而不是挡满 4 秒：万一这一下点空了，重试也别推后太久。
                        chooserAt = now - (WATCH_CHOOSER_GAP_MS - BOUND_PROBE_GAP_MS);
                        log("bound: chooser already up at uptime=" + now + "ms, handle now");
                        killChooser("bound@" + now, now);
                        try {
                            root.recycle();
                        } catch (Throwable ignored) {
                        }
                    } else {
                        log("bound: no chooser window yet");
                    }
                } catch (Throwable t) {
                    log("bound probe failed: " + t);
                }
            }
        });
    }

    private void watchSettings() {
        try {
            // 重绑时会新建服务对象，旧对象的观察者还挂在系统上：不摘掉就两个一起回调，
            // 同一次列表变更被处理两遍（实测同一次变更出现两条 "a11y list changed"，
            // 相隔 6~62 毫秒），ensureSelf 也跟着多跑一遍。
            // 次序很重要：先挂新的、成功了再摘旧的——反过来一旦新注册抛异常，
            // 旧的那条已经摘掉，就真的没人盯着列表了（极米改写列表时再没人补回来）。
            ContentResolver cr = getContentResolver();
            ContentObserver next = new ContentObserver(handler) {
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
                    false, next);
            // 摘的是静态字段里那个——它可能是上一个服务对象注册的
            if (sObserver != null) {
                try {
                    cr.unregisterContentObserver(sObserver);
                } catch (Throwable ignored) {
                }
                log("a11y watch: old observer let go");
            }
            sObserver = next;
            sObserverOwner = instanceId;
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
            // 只在列表真的变了才打印：开机那阵子同一次写回会触发好几次回调，
            // 每次打一整行完整列表会把真信号埋掉（两天攒了 218 行）。
            boolean listChanged = (cur == null) ? (LAST_LIST_LOGGED != null)
                    : !cur.equals(LAST_LIST_LOGGED);
            if (listChanged) {
                LAST_LIST_LOGGED = cur;
                logLine(context, "ensure cur=" + (cur == null ? "null" : cur));
            }
            // 只管自己。别人（包括 Aerial 自己的守护）要不要开无障碍，是用户的授权决定，
            // 我们不替他做——那等于悄悄给另一个应用发了一张无障碍权限。
            if (cur != null && cur.contains(flat)) {
                if (!cur.contains("app.orchard.tv/") && listChanged) {
                    logLine(context, "warn: a11y list has no aerial guard, left alone");
                }
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
                // 刚把自己写回列表的这几秒里事件最容易丢，记下来让看门狗临时加密
                RESTORED_AT = SystemClock.elapsedRealtime();
            }
            try {
                // 已经是 1 就别再写：每写一次都会触发一次列表变更回调，白跑一轮 ensureSelf
                if (Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                    Settings.Secure.putInt(context.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                }
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

    // Aerial 只要到过一次前台，就说明桌面自己能起来，这次开机不用管了
    private boolean everSawAerial = false;
    // 开机动画结束之后，连续几次看到"该有画面却是空的"
    private int stall = 0;
    // 上一次"救"的时刻（只放在内存里：重启后进程重来，天然归零，不需要任何跨开机的记号）
    private long lastPullAt = 0L;
    // 本回合（一次开机尾巴 / 一次唤醒）已经救了几次
    private int episodePulls = 0;
    // 每次重绑都会产生一个新的服务对象；被顶替的那个对象里的循环还活着，
    // 但它的无障碍连接已经废了、读前台永远是 null，会误判"黑屏"去拉 Aerial。
    // 只让最新那个对象做救援，旧的发现序号变了就静默退出。
    private static volatile int INSTANCE_SEQ = 0;
    private int instanceId = 0;

    /** 我是不是当前最新的那个服务对象；不是就别再动作了 */
    private boolean superseded() {
        return instanceId != INSTANCE_SEQ;
    }

    /** 一个新回合开始：开机尾巴、或者一次唤醒。额度按回合算，不按进程算——
     *  进程可以连着活好几天，拿进程当回合的话额度用完就永远不再救。 */
    private void startEpisode() {
        episodePulls = 0;
    }

    private android.content.SharedPreferences prefs() {
        return createDeviceProtectedStorageContext().getSharedPreferences("guard", android.content.Context.MODE_PRIVATE);
    }

    /**
     * 「本次开机已经见过 Aerial」要落盘，不能被重绑弄丢。
     *
     * 只在同一开机内继承，而且只桥接两分钟内的短空档：开机标识（boot_id）每次开机都变，
     * 所以跨重启、跨关机再开，天然不继承——那两种情况必须重新判黑屏。
     * 唤醒（深度休眠）时由 clearSeen() 主动清掉：睡了一觉「见过」就不再作数。
     */
    private static final long SEEN_TTL_MS = 120000L;

    private void markSeen() {
        everSawAerial = true;
        try {
            String bid = bootId();
            prefs().edit()
                    .putString("seen_boot", bid == null ? "" : bid)
                    .putLong("seen_at", SystemClock.elapsedRealtime())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void clearSeen() {
        everSawAerial = false;
        try {
            prefs().edit().remove("seen_boot").remove("seen_at").apply();
        } catch (Throwable ignored) {
        }
    }

    private boolean inheritSeen() {
        try {
            String bid = bootId();
            String sb = prefs().getString("seen_boot", "");
            long sat = prefs().getLong("seen_at", 0L);
            if (bid == null || sb == null || !bid.equals(sb) || sat <= 0L) {
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            return now >= sat && now - sat <= SEEN_TTL_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 唯一的一道闸：距离上一次"救"够久、且本回合没超过次数上限，才允许再拉一次。
     *
     * 冷却判断跨进程也要有效（实测极米会在开机时改无障碍列表、服务重绑，甚至换掉进程，
     * 换成内存变量后 1.5 秒内拉过两次 Aerial），所以"上次几点拉的"要落盘。
     * 落盘的写法刻意不用墙钟、也不存固定机器标识：存的是
     *   上次那次的开机标识（运行时读 /proc/sys/kernel/random/boot_id）
     * + 上次那次的开机内时钟（SystemClock.elapsedRealtime）
     * 只有"两次开机标识相同、且开机内时钟差 < 冷却"才算刚拉过。开机标识每次都变，
     * 所以跨重启天然放行；读不到标识就一律放行——宁可多拉一次，也不能把该救的那次吞掉。
     */
    private boolean allowPull() {
        long now = SystemClock.elapsedRealtime();
        String bid = bootId();
        String lastBid = prefs().getString("pull_boot", "");
        long lastAt = prefs().getLong("pull_at", 0L);
        boolean sameBoot = bid != null && bid.equals(lastBid);
        if (sameBoot && lastAt > 0 && now >= lastAt && now - lastAt < PULL_COOLDOWN_MS) {
            return false;
        }
        if (episodePulls >= PULL_CAP) {
            return false;
        }
        markPull(now, bid);
        episodePulls++;
        return true;
    }

    /** 记下"刚刚拉过"：内存一份（本进程用），落盘一份（跨进程、跨服务重绑用） */
    private void markPull(long now, String bid) {
        lastPullAt = now;
        try {
            prefs().edit().putString("pull_boot", bid == null ? "" : bid).putLong("pull_at", now).apply();
        } catch (Throwable ignored) {
        }
    }

    private String bootId() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                    "/proc/sys/kernel/random/boot_id"));
            try {
                String line = r.readLine();
                return line == null ? null : line.trim();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            if (superseded()) {
                log("superseded instance, stand down");
                return;
            }
            if (now - startedAt > WINDOW_MS) {
                log("window closed");
                return;
            }
            // 只有开机 10 分钟内才救。日常重新启用服务时 uptime 早就超了，一律不动。
            // 唤醒后的黑屏不在这里管：这条循环只活 3 分钟，几小时后那次唤醒它早停了（watch 才是常驻的）。
            if (now > 600000L) {
                log("uptime " + now + " too long, stand down");
                return;
            }
            String front = frontPackage();
            if (front != null && AERIAL_PKG.equals(front)) {
                markSeen();
                startEpisode();
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
            // 开机动画已经收掉了，还看不到界面：这种情况才算"该出来没出来"。
            // 注意只认 front==null：极米设置（newsettings）在前台是有人自己打开的，不是黑屏，
            // 早先把 newsettings 也算成"卡住"，结果用户开着设置页就被拉回桌面、还多记一次"救黑屏"。
            boolean stuck = front == null;
            if (!stuck) {
                stall = 0;
                log("boot finished, front=" + front + ", leave alone");
                handler.postDelayed(this, CHECK_MS);
                return;
            }
            stall++;
            log("boot finished but no usable window (" + stall + "/2, front=null)");
            if (stall >= 2) {
                if (!allowPull()) {
                    log("no usable window, but pull is cooling down or capped (" + episodePulls + "), wait");
                    handler.postDelayed(this, CHECK_MS);
                    return;
                }
                log("boot finished and still nothing, aerial never in front, pulling aerial");
                stall = 0;
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

    private void pull() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(AERIAL_PKG, AERIAL_CLS));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            // 开机不再弹自己的界面，只在计数里留下这一次（打开应用就能看到"上次救黑屏"）。
            // 按回合记：同一回合里的重试不该被算成"救了三次"。
            if (episodePulls <= 1) {
                Stats.rescue(this);
            }
        } catch (Throwable t) {
            log("startActivity failed: " + t);
        }
    }

    /** 点完「仅此一次」立刻把 Aerial 请上来，抢在弹框自己启动极米桌面那一下之前。
     *  无障碍服务本身豁免后台启动限制，不需要 overlay。 */
    private void pullNow() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(AERIAL_PKG, AERIAL_CLS));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            log("pull: start aerial right after click 仅此一次");
            // 记一笔（含落盘），免得紧接着的"救黑屏"判定在几秒内又拉一次——
            // 服务在点框之后常常会重绑/换进程，只记内存挡不住第二次
            markPull(SystemClock.elapsedRealtime(), bootId());
        } catch (Throwable t) {
            log("pullNow failed: " + t);
        }
    }

    /** 处理选择框时用：只把 Aerial 拉到前台，不弹提醒页 */
    private void pullQuiet() {
        try {
            // 按 HOME 之后应用在后台，直接 startActivity 会被系统挡住（实测前台一直是 null）。
            // 先挂一个 1px 的无障碍 overlay，让系统认为本应用有可见窗口，再启动就能过。
            showOverlay();
            Intent i = new Intent();
            i.setComponent(new ComponentName(AERIAL_PKG, AERIAL_CLS));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            log("pullQuiet failed: " + t);
        }
    }

    private View overlay;

    private void showOverlay() {
        if (overlay != null) {
            return;
        }
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            View v = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.START | Gravity.TOP;
            wm.addView(v, lp);
            overlay = v;
            log("overlay: added");
        } catch (Throwable t) {
            log("overlay: add failed " + t);
        }
    }

    private void hideOverlay() {
        if (overlay == null) {
            return;
        }
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) {
                wm.removeView(overlay);
            }
            log("overlay: removed");
        } catch (Throwable t) {
            log("overlay: remove failed " + t);
        }
        overlay = null;
    }


    private static final String XGIMI_FW = "com.xgimi.xgimiframeworkui";
    /** 极米原桌面：弹框默认选中的就是它，被它顶上来不算到站 */
    private static final String XGIMI_HOME = "com.xgimi.home";
    private static final String ANDROID_FW = "android";
    /** 系统界面：唤醒瞬间会短暂当前台，判"有没有人在用设备"时不算数 */
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String AOSP_CHOOSER = "com.android.internal.app.ResolverActivity";
    /** 连续事件冷却：typeWindowContentChanged 会连刷，3 秒内只处理一次 */
    private static final long CHOOSER_COOLDOWN_MS = 3000L;
    /** 弹框窗口出现还看不到时，80ms 一轮继续找（按钮变亮就在这一小段时间里，间隔太大就白等） */
    private static final long CHOOSER_POLL_MS = 80L;
    /** 点完候选行之后最多再等这么多轮（80ms × 16 ≈ 1.3 秒）就让位给"取消"兜底 */
    private static final int ONCE_MAX_ROUND = 16;
    private long chooserAt = 0L;

    /**
     * 弹框里"哪一行是 Aerial、哪个按钮是仅此一次"不靠写死中文：
     * 行名用系统给的应用标签（随系统语言变），按钮先认控件 ID（button_once 与语言无关），
     * 两条都认不来时才退回写死的文字。标签第一次用到才读，读到就缓存，不占每轮的时间。
     */
    private static final long LABEL_RETRY_MS = 3000L;
    private final java.util.HashMap<String, String> labels = new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> labelRetryAt = new java.util.HashMap<>();

    /** 某个包在弹框里显示的名字；读不到返回 null，调用方退回旧的文字判据 */
    private String labelOf(String pkg) {
        String cached = labels.get(pkg);
        if (cached != null) {
            return cached;
        }
        long now = SystemClock.elapsedRealtime();
        Long retryAt = labelRetryAt.get(pkg);
        if (retryAt != null && now < retryAt) {
            return null;
        }
        labelRetryAt.put(pkg, now + LABEL_RETRY_MS);
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = ai == null ? null : pm.getApplicationLabel(ai);
            String s = label == null ? null : label.toString().trim();
            if (s != null && s.length() > 0) {
                labels.put(pkg, s);
                log("label: " + pkg + " -> \"" + s + "\"");
                return s;
            }
        } catch (Throwable t) {
            // 包还没装：不作数，等它装好（下面的广播会清缓存）或者冷却后再读
        }
        return null;
    }

    /** 装/卸 Aerial、换系统语言之后标签会变，清掉重读一次 */
    private void invalidateLabels(String why) {
        labels.clear();
        labelRetryAt.clear();
        log("label: cleared (" + why + ")");
    }

    /** 框里的 APK 装上了/换了/卸了：清掉缓存的标签，下次弹框重新读。
     *  接收器本体在 onServiceConnected 里建（要跟着实例走，注册用的对象存在静态字段里）。 */

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 换语言/换区域的标签是另一个字符串，缓存作废
        invalidateLabels("configuration changed");
    }


    /** 事件偶尔会漏（实测 10 次漏 2 次），所以再加一个 1 秒一次的看门狗兜底 */
    private static final long WATCH_MS = 1000L;
    /**
     * 刚绑上的一小段时间最容易丢事件（开机后极米反复改写无障碍列表，服务刚恢复进去，
     * 那一下的事件就到不了），这段窗口里把看门狗加密到 400ms，把"事件漏了要等 1 秒"
     * 压到 0.4 秒；平时仍按 1 秒跑，不改常驻开销。
     */
    private static final long WATCH_FAST_MS = 400L;
    private static final long WATCH_FAST_WIN_MS = 30000L;
    /** 最近一次把自己写回无障碍列表的时刻；那之后的一小段是丢事件高危期 */
    static volatile long RESTORED_AT = 0L;
    private static final long WATCH_CHOOSER_GAP_MS = 4000L;
    /** 绑上时自己已经处理过框了，让看门狗晚这么久再起同样的探针：
     *  既不重复点行（反复点会把「变亮」的过程重置），也不把第一次点击失败时的重试推后太多 */
    private static final long BOUND_PROBE_GAP_MS = 2000L;

    /** 真休眠不再靠"两次 tick 间隔多大"去猜，而是问系统要账：
     *  elapsedRealtime 含深度休眠、uptimeMillis 不含，二者之差就是本次开机累计睡过的时长。
     *  两次 watch 之间这个差变大了，就说明设备真睡过——睡 20 秒也认，不再拿 60 秒当下限
     *  （关机后一两分钟就开机，前者根本不进这个分支，前台停在哪都没人救）。 */
    private static final long WAKE_DEEP_MS = 800L;
    /** 兜底：万一某台机器 uptimeMillis 语义不正常（差值恒为 0），仍按"空档超过 20 秒"兜一次。
     *  正常 tick 间隔是 0.4~1 秒，离它很远 */
    private static final long WAKE_GAP_MS = 20000L;
    /** pullQuiet 挂的 1px 窗口没有 Settle 帮忙收尾，自己 6 秒后清掉 */
    private static final long WAKE_HIDE_MS = 6000L;

    private long lastWatchAt = 0L;
    private long lastDeepAt = 0L;
    // 唤醒窗口里连续几轮看不到任何前台：一次抖动不算黑屏，要连续两轮
    private int wakeNull = 0;
    /** 唤醒之后要救的这一回合开着没有。
     *  旧写法拿 20 秒当门槛：时间一到就 window closed，慢唤醒那次白等、也再没有补救。
     *  这里改成按结果收尾——见到 Aerial、三次额度用完、或者发现别人在用设备（连续两轮），
     *  三条都不用猜秒数，慢就多等几轮，快就早点收工。 */
    private boolean wakeOpen = false;
    /** 唤醒后前台连续几轮是"既不是 Aerial、也不是极米桌面/弹框"的别人 */
    private int wakeOther = 0;

    private final Runnable wakeHide = new Runnable() {
        @Override
        public void run() {
            hideOverlay();
        }
    };

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (superseded()) {
                log("superseded instance, watch off");
                hideOverlay();
                return;
            }
            try {
                String front = frontPackage();
                long now = SystemClock.elapsedRealtime();
                long gap = lastWatchAt == 0L ? 0L : now - lastWatchAt;
                lastWatchAt = now;
                // 系统自己记的"本次开机累计睡了多久"：含休眠的钟减去不含休眠的钟
                long deep = now - SystemClock.uptimeMillis();
                long deepDelta = lastDeepAt == 0L ? 0L : deep - lastDeepAt;
                lastDeepAt = deep;
                if (deepDelta > WAKE_DEEP_MS || gap > WAKE_GAP_MS) {
                    // 睡了一觉，前面「见过 Aerial」不再作数；黑屏和落在极米桌面都交给同一套判据
                    wakeOpen = true;
                    clearSeen();
                    stall = 0;
                    wakeNull = 0;
                    wakeOther = 0;
                    startEpisode();
                    log("wake: slept " + Math.max(deepDelta, gap) + "ms (deep +" + deepDelta
                            + ", gap " + gap + ", total " + deep
                            + "), keep watching until aerial is in front");
                }
                if (XGIMI_FW.equals(front) && now - chooserAt > WATCH_CHOOSER_GAP_MS) {
                    // 前台突然变成极米框架窗口：事件漏了，这里补上
                    chooserAt = now;
                    log("watch: front=" + front + ", event missed, handle as chooser");
                    killChooser("watch/" + front, now);
                } else if (front == null && now - chooserAt > WATCH_CHOOSER_GAP_MS) {
                    // 前台是空的（黑屏）：看看窗口列表里是不是还压着一个看不见的弹框
                    AccessibilityNodeInfo r = chooserRootQuiet();
                    if (r != null) {
                        chooserAt = now;
                        log("watch: front=null but chooser window alive, handle it");
                        killChooser("watch/null", now);
                        try {
                            r.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                // 醒来后要把 Aerial 请回来：落在极米桌面（睡前栈顶就是它），或者干脆没画面（黑屏），
                // 两种情况都是同一个动作，走同一道闸。别的 app 一律不抢，免得把人家正在看的东西顶掉。
                // 这里不看屏幕亮没亮：Handler 能跑到这一轮就说明设备已经醒了（真睡着时它是冻住的）。
                // 唤醒爬升期（屏还没亮）就把 Aerial 顶上去，亮屏第一帧才是 Aerial；等屏亮再拉会先露原桌面。
                // 拉完不清窗口：万一这次 startActivity 被系统挡掉，5 秒冷却过后还有再试一次的机会。
                if (wakeOpen) {
                    if (front == null) {
                        wakeNull++;
                    } else {
                        wakeNull = 0;
                    }
                    // 既不是 Aerial，也不是极米桌面/极米弹框：有人自己在用别的应用。
                    // 系统界面（systemui）在唤醒瞬间会短暂顶上来，它不算"有人在用"，否则会误收工。
                    boolean someoneElse = front != null && !AERIAL_PKG.equals(front)
                            && !XGIMI_HOME.equals(front) && !XGIMI_FW.equals(front)
                            && !SYSTEM_UI.equals(front);
                    wakeOther = someoneElse ? wakeOther + 1 : 0;
                    if (AERIAL_PKG.equals(front)) {
                        wakeOpen = false;
                        log("wake: aerial in front, close");
                    } else if (episodePulls >= PULL_CAP) {
                        // 额度用完还没到站：收工，交给下一次唤醒/下一次弹框，别无限拉
                        wakeOpen = false;
                        log("wake: cap reached (" + episodePulls + "/" + PULL_CAP + "), close");
                    } else if (wakeOther >= 3) {
                        // 连着三轮都是别人的应用：用户自己在用，别抢（这也是"醒着但没人需要救"的收尾条件）
                        wakeOpen = false;
                        log("wake: front=" + front + ", someone in charge, close");
                    } else if ((XGIMI_HOME.equals(front) || wakeNull >= 2)
                            && now - chooserAt > WATCH_CHOOSER_GAP_MS
                            && allowPull()) {
                        log("wake: front=" + front + ", pull quiet");
                        pullQuiet();
                        if (handler != null) {
                            handler.postDelayed(wakeHide, WAKE_HIDE_MS);
                        }
                    }
                }
            } catch (Throwable t) {
                log("watch failed: " + t);
            }
            if (handler != null) {
                long now = SystemClock.elapsedRealtime();
                long anchor = Math.max(startedAt, RESTORED_AT);
                handler.postDelayed(this, now - anchor < WATCH_FAST_WIN_MS ? WATCH_FAST_MS : WATCH_MS);
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (superseded()) {
            return;
        }
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence p = event.getPackageName();
        CharSequence c = event.getClassName();
        if (p == null || c == null) {
            return;
        }
        String pkg = p.toString();
        String cls = c.toString();
        if (!isHomeChooser(pkg, cls)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - chooserAt < CHOOSER_COOLDOWN_MS) {
            return;
        }
        chooserAt = now;
        killChooser(pkg + "/" + cls, now);
    }

    /** HOME 选择框：极米自己的，或安卓自己的 */
    private boolean isHomeChooser(String pkg, String cls) {
        if (XGIMI_FW.equals(pkg) && cls.endsWith(".resolver.ResolverActivity")) {
            return true;
        }
        return ANDROID_FW.equals(pkg) && AOSP_CHOOSER.equals(cls);
    }

    /**
     * 不用返回键：点弹框自己的控件把它关掉。
     * 实测弹框的"仅此一次/始终/取消"在没选中候选前是灰的、点不动，
     * 所以先点中 Aerial 那一行（按钮随即变亮），再点"仅此一次"——弹框自己 finish 并启动 Aerial，
     * 不写任何首选记录。两条都走不通时退化到点"取消"（它本身就等价于对这个窗口按返回）。
     * 必须抢在被 Aerial 盖住之前动手：一旦盖住，getWindows() 里就看不到这个弹框了。
     *
     * 1.9.26 的出场判断：Aerial ≥ 1.97 自己会发 BACK 关这个框（#1523），这条路上我们一步都不做——
     * 不点，也不读窗口列表：读取会落到那个"窗口在、焦点在、但什么都没画"的框上，
     * 它本来就不响应，我们多碰一次只会让自己更难堪，没有半点好处。
     * Aerial < 1.97 不会关这个框，仍照 1.9.22 原样立刻点。
     */
    private void killChooser(final String who, final long eventAt) {
        if (handler == null) {
            return;
        }
        if (aerialClosesChooser()) {
            log("chooser seen (" + who + "), aerial handles it itself, stay out");
            return;
        }
        log("chooser seen (" + who + "), aerial too old for this, run our chain");
        handler.post(new Probe(who, eventAt, 0));
    }

    /** 缓存：Aerial 是不是已经带 #1523（1.97 起） */
    private Boolean aerialHasBack = null;

    /** 查 Aerial 自己的版本号决定谁出场；查不到就当我们上（老版本来收框） */
    private boolean aerialClosesChooser() {
        if (aerialHasBack != null) {
            return aerialHasBack;
        }
        boolean ok = false;
        try {
            String vn = getPackageManager().getPackageInfo(AERIAL_PKG, 0).versionName;
            ok = versionAtLeast(vn, 1, 97);
            log("aerial version " + vn + " -> " + (ok ? "stand down" : "we handle the chooser"));
        } catch (Throwable t) {
            log("aerial version unknown: " + t);
        }
        aerialHasBack = ok;
        return ok;
    }

    /** 只按数字比：1.97 / 1.97.0 / 1.98 都算 >= 1.97 */
    private static boolean versionAtLeast(String vn, int major, int minor) {
        if (vn == null) {
            return false;
        }
        try {
            String[] parts = vn.split("\\.");
            int a = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
            int b = parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("[^0-9]", "")) : 0;
            return a > major || (a == major && b >= minor);
        } catch (Throwable t) {
            return false;
        }
    }

    private final class Probe implements Runnable {
        private final String who;
        private final long at;
        private final int n;
        /**
         * 候选行到底点进去没有。只有点进去了，"亮着的按钮"才可能是 Aerial；
         * 点行失败时按钮亮着往往是"极米桌面"被选中的结果，这时点它就是启动错桌面。
         */
        private final boolean rowOk;

        Probe(String who, long at, int n) {
            this(who, at, n, false);
        }

        Probe(String who, long at, int n, boolean rowOk) {
            this.who = who;
            this.at = at;
            this.n = n;
            this.rowOk = rowOk;
        }

        @Override
        public void run() {
            // 被重绑顶替的旧对象已经读不到前台（getRootInActiveWindow 恒 return null），
            // 留着这条链会误判"框还在"一直点；新对象自己被绑上时会先看一眼有没有框。
            if (superseded()) {
                return;
            }
            long ms = SystemClock.elapsedRealtime() - at;
            // 已经在 Aerial 了就别再动：省掉空转，也不跟 Aerial 自己的守护抢动作
            if (n > 0 && AERIAL_PKG.equals(frontPackage())) {
                log("probe " + who + ": aerial already in front, stop at +" + ms + "ms");
                return;
            }
            AccessibilityNodeInfo root = (n == 0) ? chooserRoot(who) : chooserRootQuiet();
            if (root == null) {
                // 弹框窗口还没出现（或已经被盖住看不见了），继续等
                if (n < 24 && handler != null) {
                    handler.postDelayed(new Probe(who, at, n + 1, rowOk), CHOOSER_POLL_MS);
                } else if (n >= 24) {
                    log("probe " + who + ": chooser never showed up, gave up at +" + ms + "ms");
                }
                return;
            }
            try {
                Holder h = new Holder();
                scanChooser(root, h);

                if (n == 0) {
                    // 第一轮：只点一次候选行。反复点会把"按钮变亮"的过程一直重置。
                    log("probe " + who + ": chooser window at +" + ms + "ms");
                    if (DUMP_CHOOSER_TREE) {
                        dumpChooserTree(root, who);
                    }
                    log("probe " + who + ": Aerial[" + rowState(h.aerialRow) + "] 极米桌面["
                            + rowState(h.xgimiRow) + "] 仅此一次enabled="
                            + (h.once != null && h.once.isEnabled()) + " at +" + ms + "ms");
                    log("probe " + who + ": match aerial=" + h.aerialVia
                            + " xgimi=" + h.xgimiVia + " once=" + h.onceVia + " at +" + ms + "ms");
                    boolean rowClick = clickUp(h.aerialRow);
                    log("probe " + who + ": click row Aerial=" + rowClick + " at +" + ms + "ms");
                    // 框刚起来那阵子窗口还不收输入，这一下会直接返回 false，
                    // 按钮就永远等不到变亮。失败就隔 200ms 再点一次候选行，最多两次。
                    if (!rowClick && handler != null) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                retryRow(who, at, 1);
                            }
                        }, 200L);
                    }
                    // 认不出"仅此一次"（既没有 ID、文字也不认识）：不等按钮变亮，直接点"取消"，
                    // 框照样关掉，Aerial 本来就在下面，再自己补拉一次——绝不赌哪个按钮是"仅此一次"
                    if (h.once == null && h.cancel != null && clickUp(h.cancel)) {
                        log("probe " + who + ": once unknown, click cancel at +" + ms + "ms");
                        pullNow();
                        Stats.home(BootA11yService.this, SystemClock.elapsedRealtime() - at, "取消兜底");
                        scheduleSettle(who, at);
                        return;
                    }
                    if (handler != null) {
                        handler.postDelayed(new Probe(who, at, 1, rowClick), CHOOSER_POLL_MS);
                    }
                    return;
                }

                // 按钮一亮就点：不先查 isEnabled，直接点，返回 false 就下一轮再来，
                // 这样"变亮的那个瞬间"就被抓住，不用等下一整轮
                // 但前提是候选行确实点进去了；点行失败时按钮亮着可能是"极米桌面"被选中，
                // 这时候点「仅此一次」等于启动极米桌面——宁可等超时走取消兜底
                if (rowOk && h.once != null && clickUp(h.once)) {
                    log("probe " + who + ": click 仅此一次 at +" + ms + "ms (round " + n + ")");
                    pullNow();
                    // 开机后系统忙，ACTION_CLICK 可能被吞掉：框没走就再点，别只点一次就等它自己走
                    if (handler != null) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                verifyDismiss(who, at, 1);
                            }
                        }, 600L);
                    }
                    scheduleSettle(who, at);
                    return;
                }
                if (n < ONCE_MAX_ROUND && handler != null) {
                    handler.postDelayed(new Probe(who, at, n + 1, rowOk), CHOOSER_POLL_MS);
                    return;
                }
                // 一直等不到按钮变亮：宁可点"取消"把这个窗口关掉，也不盲点"始终"记错桌面
                AccessibilityNodeInfo cancel = findByText(root, "取消", true);
                if (cancel == null) {
                    cancel = findByText(root, "Cancel", true);
                }
                if (cancel == null) {
                    cancel = findByText(root, "Отмена", true);
                }
                if (cancel == null) {
                    cancel = h.cancel;
                }
                boolean c = clickUp(cancel);
                log("probe " + who + ": 仅此一次 never enabled, click 取消=" + c + " at +"
                        + (SystemClock.elapsedRealtime() - at) + "ms");
                if (c) {
                    Stats.home(BootA11yService.this, SystemClock.elapsedRealtime() - at, "取消兜底");
                    scheduleSettle(who, at);
                }
            } catch (Throwable t) {
                log("probe " + who + " failed: " + t);
            } finally {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 一轮扫描的产物：候选行、按钮。每轮只遍历一次节点树。 */
    private static final class Holder {
        AccessibilityNodeInfo aerialRow;
        AccessibilityNodeInfo xgimiRow;
        AccessibilityNodeInfo once;
        /** 按顺序认出来的"取消"，文字认不到时用它 */
        AccessibilityNodeInfo cancel;
        /** 这一轮是靠什么认出来的：id / label / text / order，写进日志方便以后排查 */
        String aerialVia;
        String xgimiVia;
        String onceVia;
    }

    /** 诊断用：把弹框窗口里的节点连同控件 ID 打一遍，看清这家固件给按钮起了什么名字 */
    private static final boolean DUMP_CHOOSER_TREE = false;

    private void dumpChooserTree(AccessibilityNodeInfo root, String who) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int n = 0;
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 200 && n < 40) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            String text = node.getText() == null ? null : node.getText().toString();
            String desc = node.getContentDescription() == null
                    ? null : node.getContentDescription().toString();
            String vid = node.getViewIdResourceName();
            boolean hasId = vid != null && vid.length() > 0;
            boolean hasText = (text != null && text.length() > 0) || (desc != null && desc.length() > 0);
            if (hasId || hasText || node.isClickable()) {
                n++;
                android.graphics.Rect r = new android.graphics.Rect();
                node.getBoundsInScreen(r);
                log("node " + who + ": id=\"" + (hasId ? vid : "") + "\" cls=" + str(node.getClassName())
                        + " txt=\"" + (text == null ? "" : text) + "\" desc=\""
                        + (desc == null ? "" : desc) + "\" clk=" + node.isClickable()
                        + " en=" + node.isEnabled() + " foc=" + node.isFocusable()
                        + " sel=" + node.isSelected() + " bounds=" + r.left + "," + r.top
                        + "," + r.right + "," + r.bottom);
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        log("node " + who + ": dumped " + n + " nodes");
    }

    /** AOSP 的选择框按钮 ID 是 android:id/button_once；厂商定制版前缀不同，按这个名字认 */
    private static boolean isOnceId(String vid) {
        return vid != null && vid.contains("button_once");
    }

    /** "仅此一次"在各语言下的写法；AOSP 的框先靠 button_once 的 ID，这里兜定制界面 */
    private static final String[] ONCE_TEXTS = {
            "仅此一次", "仅一次", "Just once", "Once", "Только сейчас", "Один раз",
            "Лише один раз", "Тек бір рет",
    };

    private static boolean isOnceText(String text, String desc) {
        for (String t : ONCE_TEXTS) {
            if (matches(text, desc, t, true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一次广度遍历，把 Aerial 行 / 极米桌面行 / 仅此一次 全找出来，找齐就提前收手。
     * 认出哪个节点不写死中文：行用系统给的应用标签（跟着语言走），按钮先认控件 ID，
     * 再认几种语言的"仅此一次"文字；都认不出来时绝不按顺序猜（有的固件「始终」排第一，
     * 猜错就会写错首选桌面），只用"选候选前唯一亮着的那个按钮 = 取消"这条安全判据。
     */
    private void scanChooser(AccessibilityNodeInfo root, Holder h) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        java.util.ArrayList<AccessibilityNodeInfo> buttons = new java.util.ArrayList<>();
        String aerialLabel = labelOf(AERIAL_PKG);
        String xgimiLabel = labelOf(XGIMI_HOME);
        AccessibilityNodeInfo aerialText = null;
        AccessibilityNodeInfo xgimiText = null;
        AccessibilityNodeInfo onceText = null;
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 250) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            String text = node.getText() == null ? null : node.getText().toString();
            String desc = node.getContentDescription() == null
                    ? null : node.getContentDescription().toString();
            String vid = node.getViewIdResourceName();
            if (str(node.getClassName()).endsWith("Button")) {
                buttons.add(node);
            }
            if (onceText == null && isOnceId(vid)) {
                onceText = node;
                h.onceVia = "id:" + vid;
            } else if (onceText == null && isOnceText(text, desc)) {
                onceText = node;
                h.onceVia = "text";
            }
            if (aerialText == null) {
                if (aerialLabel != null && matches(text, desc, aerialLabel, false)) {
                    aerialText = node;
                    h.aerialVia = "label:\"" + aerialLabel + "\"";
                } else if (matches(text, desc, "Aerial", false)) {
                    aerialText = node;
                    h.aerialVia = "text";
                }
            }
            if (xgimiText == null) {
                if (xgimiLabel != null && matches(text, desc, xgimiLabel, false)) {
                    xgimiText = node;
                    h.xgimiVia = "label:\"" + xgimiLabel + "\"";
                } else if (matches(text, desc, "极米桌面", false)) {
                    xgimiText = node;
                    h.xgimiVia = "text";
                }
            }
            if (aerialText != null && xgimiText != null && onceText != null && buttons.size() >= 3) {
                break;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        // 只认"一定不会点错"的那个：选候选之前，三个按钮里只有"取消"是亮的，另外两个（仅此一次/始终）都是灰的。
        // 所以亮着的那个就是"取消"，可以放心点——即使换语言认不出字，最坏也只是取消掉这个框，
        // 绝不会点到"始终"去写错首选记录。反过来不能按上下顺序去猜"哪个是仅此一次"：
        // 有的固件可能把"始终"排在第一个，猜错就是记错桌面。顺序不作为判据。
        if (buttons.size() == 3) {
            AccessibilityNodeInfo onlyEnabled = null;
            int enabledCount = 0;
            for (AccessibilityNodeInfo b : buttons) {
                if (b.isEnabled()) {
                    enabledCount++;
                    onlyEnabled = b;
                }
            }
            if (enabledCount == 1) {
                h.cancel = onlyEnabled;
                h.onceVia = (onceText == null ? "none" : h.onceVia) + " (cancel:onlyEnabled)";
            }
        }
        h.aerialRow = clickableAncestor(aerialText);
        h.xgimiRow = clickableAncestor(xgimiText);
        h.once = onceText;
    }

    private static boolean matches(String text, String desc, String needle, boolean exact) {
        if (exact) {
            return needle.equals(text) || needle.equals(desc);
        }
        return (text != null && text.contains(needle)) || (desc != null && desc.contains(needle));
    }

    /**
     * 点候选行没成功（窗口还没收输入）：Aerial 已经在前台就收工，否则再点一次行。
     */
    private void retryRow(String who, long at, int k) {
        if (k > 2 || handler == null || superseded()) {
            return;
        }
        if (AERIAL_PKG.equals(frontPackage())) {
            log("row retry #" + k + ": aerial already in front, stop");
            return;
        }
        AccessibilityNodeInfo root = chooserRootQuiet();
        if (root == null) {
            return;
        }
        try {
            Holder h = new Holder();
            scanChooser(root, h);
            if (h.once != null && clickUp(h.once)) {
                log("click 仅此一次 at +" + (SystemClock.elapsedRealtime() - at) + "ms (after row retry #" + k + ")");
                pullNow();
                scheduleSettle(who, at);
                return;
            }
            boolean ok = clickUp(h.aerialRow);
            log("click row Aerial again #" + k + "=" + ok + " at +"
                    + (SystemClock.elapsedRealtime() - at) + "ms");
            if (ok) {
                handler.postDelayed(new Probe(who, at, 1, true), CHOOSER_POLL_MS);
                return;
            }
        } catch (Throwable t) {
            log("retryRow failed: " + t);
        } finally {
            try {
                root.recycle();
            } catch (Throwable ignored) {
            }
        }
        final String w = who;
        final long a = at;
        final int nk = k + 1;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                retryRow(w, a, nk);
            }
        }, 200L);
    }

    private void scheduleSettle(String who, long at) {
        if (handler != null) {
            handler.postDelayed(new Settle(who, at, 1), 250L);
        }
    }

    /**
     * 点完「仅此一次」再回头看一眼：开机那阵子系统很忙，ACTION_CLICK 有时会被吞掉，
     * 框一直留在屏幕上。没走就再点一次，最多两次——不是无脑连点：框已经走了就立刻停手。
     */
    private void verifyDismiss(String who, long at, int k) {
        if (k > 2 || handler == null || superseded()) {
            return;
        }
        String front = frontPackage();
        if (front != null && !XGIMI_FW.equals(front)) {
            log("dismiss ok at +" + (SystemClock.elapsedRealtime() - at) + "ms (front=" + front + ")");
            return;
        }
        AccessibilityNodeInfo root = chooserRootQuiet();
        if (root == null) {
            return;
        }
        try {
            Holder h = new Holder();
            scanChooser(root, h);
            if (h.once != null && clickUp(h.once)) {
                log("click 仅此一次 again #" + k + " at +" + (SystemClock.elapsedRealtime() - at)
                        + "ms (chooser still up)");
                final String w = who;
                final long a = at;
                final int nk = k + 1;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        verifyDismiss(w, a, nk);
                    }
                }, 400L);
            }
        } catch (Throwable t) {
            log("verifyDismiss failed: " + t);
        } finally {
            try {
                root.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 从所有窗口里挑出弹框那个窗口的根节点——即使它被 Aerial 盖住、没有焦点。
     * 拿到之后可以直接对它发 ACTION_CLICK，动作直达那个窗口，不看谁在前台。
     */
    private AccessibilityNodeInfo chooserRoot(String who) {
        try {
            java.util.List<AccessibilityWindowInfo> wins = getWindows();
            StringBuilder sb = new StringBuilder("windows:");
            AccessibilityNodeInfo fallback = null;
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) {
                        continue;
                    }
                    String p = str(r.getPackageName());
                    sb.append(' ').append(p).append('/').append(str(r.getClassName()))
                            .append(w.isActive() ? "(active)" : "")
                            .append(w.isFocused() ? "(focused)" : "");
                    if (XGIMI_FW.equals(p)) {
                        log("probe " + who + ": " + sb);
                        return r;
                    }
                    if (fallback == null) {
                        fallback = r;
                    } else {
                        try {
                            r.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            log("probe " + who + ": " + sb);
            if (fallback != null) {
                String p = str(fallback.getPackageName());
                if (AERIAL_PKG.equals(p)) {
                    log("probe " + who + ": only " + p + ", chooser gone");
                    try {
                        fallback.recycle();
                    } catch (Throwable ignored) {
                    }
                    return null;
                }
                return fallback;
            }
        } catch (Throwable t) {
            log("chooserRoot failed: " + t);
        }
        return getRootInActiveWindow();
    }

    /** 点完之后核对前台；Aerial 没上来且没人用设备时就把它请上来 */
    /** 一次处理只记一条统计：按 et 去重，避免 Settle 每轮都记 */
    private long recAt = 0L;

    private final class Settle implements Runnable {
        private final String who;
        private final long at;
        private final int n;

        Settle(String who, long at, int n) {
            this.who = who;
            this.at = at;
            this.n = n;
        }

        @Override
        public void run() {
            if (superseded()) {
                hideOverlay();
                return;
            }
            long ms = SystemClock.elapsedRealtime() - at;
            String front = frontPackage();
            log("settle " + who + ": check#" + n + " front=" + front + " at +" + ms + "ms");
            if (AERIAL_PKG.equals(front)) {
                hideOverlay();
                // 这里比 tick（2 秒一轮）更早看到 Aerial：先记下"见过"，
                // 万一紧接着服务重绑，新对象也能继承，不会把刚起来的 Aerial 再拉一次
                markSeen();
                // 超过 10 秒的样本是设备睡过去把 Handler 冻住造成的，记了会污染统计
                if (recAt != at && ms <= 10000L) {
                    recAt = at;
                    Stats.home(BootA11yService.this, ms,
                            who != null && who.startsWith("watch") ? "看门狗补" : "点仅此一次");
                }
                return;
            }
            // 用户自己开起了别的 app（不是极米系）就别抢了
            if (front != null && !XGIMI_FW.equals(front) && !XGIMI_HOME.equals(front) && ms > 1500L) {
                log("settle " + who + ": front is " + front + ", stop");
                hideOverlay();
                return;
            }
            if (n > 30) {
                log("settle " + who + ": gave up");
                hideOverlay();
                return;
            }
            // 弹框的"仅此一次"会把默认选中的极米桌面顶上来（实测会闪一下），
            // Aerial 得等极米桌面自己的守护再顶回来；超过 5.6 秒还没到才自己拉
            if (ms > 5600L || (front == null && ms > 2500L)) {
                pullQuiet();
            }
            handler.postDelayed(new Settle(who, at, n + 1), 300L);
        }
    }

    /** 只看窗口列表里有没有极米的弹框窗口，不打印窗口清单（轮询里用） */
    private AccessibilityNodeInfo chooserRootQuiet() {
        try {
            java.util.List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) {
                        continue;
                    }
                    if (XGIMI_FW.equals(str(r.getPackageName()))) {
                        return r;
                    }
                    try {
                        r.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            log("chooserRootQuiet failed: " + t);
        }
        return null;
    }

    /** 广度优先找 text / desc 命中的节点；exact=true 时要求完全相等 */
    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String needle, boolean exact) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 200) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            String text = node.getText() == null ? null : node.getText().toString();
            String desc = node.getContentDescription() == null
                    ? null : node.getContentDescription().toString();
            boolean hit = exact
                    ? (needle.equals(text) || needle.equals(desc))
                    : (text != null && text.contains(needle));
            if (hit) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "-" : o.toString();
    }

    /** 从命中的节点往上找第一个可点的祖先再点（"Aerial" 那行文字本身不可点，可点的是外层 LinearLayout） */
    private boolean clickUp(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    /** 从命中的文字节点往上找那个"可点的整行"（候选行容器），找不到返回原节点 */
    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) {
                return cur;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) {
                break;
            }
            cur = parent;
        }
        return node;
    }

    /** 打印候选行的选中/焦点/可点状态，用来判断"始终"会记到谁头上 */
    private String rowState(AccessibilityNodeInfo row) {
        if (row == null) {
            return "null";
        }
        return "sel=" + row.isSelected() + " foc=" + row.isFocused()
                + " a11yFoc=" + row.isAccessibilityFocused() + " clk=" + row.isClickable();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        // 只有「最新的那个对象」下线才算真下线；旧对象被回收时不能把新对象的监听/状态摘掉
        if (instanceId == INSTANCE_SEQ) {
            BOUND = false;
        }
        if (sPacks != null && sPacksOwner == instanceId) {
            try {
                unregisterReceiver(sPacks);
            } catch (Throwable ignored) {
            }
            sPacks = null;
            sPacksOwner = 0;
        }
        if (sObserver != null && sObserverOwner == instanceId) {
            try {
                getContentResolver().unregisterContentObserver(sObserver);
            } catch (Throwable ignored) {
            }
            sObserver = null;
            sObserverOwner = 0;
        }
        hideOverlay();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instanceId == INSTANCE_SEQ) {
            BOUND = false;
        }
        return super.onUnbind(intent);
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
            File f = new File(dir, "rescue.log");
            // 墙钟在开机早期还没校准（日志里会出现 08:00:0x 一段，看着像另一天）。
            // 每行再带一个开机内时钟 u=，跨开机的时间线靠它对齐，不靠猜。
            long up = SystemClock.elapsedRealtime();
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " u=" + up + " " + msg + "\n";
            synchronized (LOG_LOCK) {
                // 日志不封顶会一直涨（两天 326 KB、2800 行）。过 512 KB 换到 .1：
                // 旧的一代直接替换，出问题时还有上一段可查，平时读尾部不受影响。
                if (f.length() > LOG_MAX_BYTES) {
                    try {
                        File old = new File(dir, "rescue.log.1");
                        if (old.exists() && !old.delete()) {
                            // 删不掉就继续往当前文件写，宁可文件长一点，也不丢日志
                            throw new java.io.IOException("cannot replace rescue.log.1");
                        }
                        if (!f.renameTo(old)) {
                            throw new java.io.IOException("cannot rename rescue.log");
                        }
                    } catch (Throwable ignored) {
                    }
                }
                FileOutputStream out = new FileOutputStream(f, true);
                try {
                    out.write(line.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }
            // 同一行再镜像到外部目录：这机器 logd 是停的，logcat 读不到，adb 只能 tail 这里
            try {
                File ext = context.getExternalFilesDir(null);
                if (ext != null) {
                    File ef = new File(ext, "rescue-ext.log");
                    if (ef.exists() && ef.length() > LOG_MAX_BYTES) {
                        ef.delete();
                    }
                    FileOutputStream eo = new FileOutputStream(ef, true);
                    try {
                        eo.write(line.getBytes("UTF-8"));
                    } finally {
                        eo.close();
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }
}
