package com.sln162.bootrescue;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 给界面看的计数：救过几次黑屏、按过几次主页键、最近几次花了多久。
 * 存在 device-protected 存储里，开机解锁之前服务也能写。
 */
final class Stats {

    private static final String KEY_HOME_COUNT = "home_count";
    private static final String KEY_HOME_RECENT = "home_recent";
    private static final String KEY_RESCUE_AT = "rescue_at";
    private static final String KEY_RESCUE_COUNT = "rescue_count";
    private static final String KEY_THEME = "theme";
    private static final int KEEP = 8;

    private Stats() {
    }

    private static SharedPreferences sp(Context c) {
        Context de = c.createDeviceProtectedStorageContext();
        return de.getSharedPreferences("guard", Context.MODE_PRIVATE);
    }

    /** 处理了一次主页键（点掉选择框）：记下走了多久、怎么处理的 */
    static void home(Context c, long ms, String how) {
        try {
            SharedPreferences p = sp(c);
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "   +" + ms + "ms   " + how;
            String old = p.getString(KEY_HOME_RECENT, "");
            String[] parts = (old == null || old.isEmpty()) ? new String[0] : old.split("\n");
            StringBuilder sb = new StringBuilder(line);
            int keep = Math.min(parts.length, KEEP - 1);
            for (int i = 0; i < keep; i++) {
                sb.append("\n").append(parts[i]);
            }
            p.edit()
                    .putInt(KEY_HOME_COUNT, (int) Math.min(1000000L, (long) p.getInt(KEY_HOME_COUNT, 0) + 1))
                    .putString(KEY_HOME_RECENT, sb.toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 开机那一次把桌面拉回来了 */
    static void rescue(Context c) {
        try {
            SharedPreferences p = sp(c);
            p.edit()
                    .putString(KEY_RESCUE_AT, new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                    .format(new Date()))
                    .putInt(KEY_RESCUE_COUNT, (int) Math.min(1000000L, (long) p.getInt(KEY_RESCUE_COUNT, 0) + 1))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    static int homeCount(Context c) {
        try {
            return sp(c).getInt(KEY_HOME_COUNT, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    static String homeRecent(Context c) {
        try {
            String v = sp(c).getString(KEY_HOME_RECENT, "");
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    static String rescueAt(Context c) {
        try {
            String v = sp(c).getString(KEY_RESCUE_AT, "");
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    static int rescueCount(Context c) {
        try {
            return sp(c).getInt(KEY_RESCUE_COUNT, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 配色：1 墨绿 / 2 米白 / 3 深蓝，按了左右键就存下来 */
    static int themeIndex(Context c, int def) {
        try {
            return sp(c).getInt(KEY_THEME, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static void setThemeIndex(Context c, int v) {
        try {
            sp(c).edit().putInt(KEY_THEME, v).apply();
        } catch (Throwable ignored) {
        }
    }
}
