package com.sln162.bootrescue;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * 手动打开时看到的界面：守护开没开、救过几次黑屏、按主页键最近几次花了多久、主页规则实际是什么。
 * 左列放状态与规则，右列放记录与日志，横过来摊满整屏。开机时不再弹这个页面。
 */
public class MainActivity extends Activity {

    /** 1 深色墨绿 / 2 浅色米白 / 3 深蓝；默认深蓝，按左右键切换并记住 */
    private static final int THEME_DEFAULT = 3;
    private static final String[] THEME_NAMES = {"墨绿", "米白", "深蓝"};

    private int theme = THEME_DEFAULT;

    private int bg;
    private int fg;
    private int dim;
    private int faint;
    private int green;
    private int red;
    private int lineColor;
    private int warn;

    private void palette() {
        theme = Stats.themeIndex(this, THEME_DEFAULT);
        if (theme < 1 || theme > 3) {
            theme = THEME_DEFAULT;
        }
        if (theme == 2) {
            bg = Color.parseColor("#F3F6F4");
            fg = Color.parseColor("#12211B");
            dim = Color.parseColor("#4E6359");
            faint = Color.parseColor("#6C8177");
            green = Color.parseColor("#0E9F6E");
            red = Color.parseColor("#C94F4F");
            lineColor = Color.parseColor("#DCE5E1");
            warn = Color.parseColor("#B7791F");
        } else if (theme == 3) {
            bg = Color.parseColor("#0D1B26");
            fg = Color.parseColor("#E8F3F7");
            dim = Color.parseColor("#83A3B4");
            faint = Color.parseColor("#64838F");
            green = Color.parseColor("#3FD8C0");
            red = Color.parseColor("#FF7A6B");
            lineColor = Color.parseColor("#1D3446");
            warn = Color.parseColor("#F2C46D");
        } else {
            bg = Color.parseColor("#0B1210");
            fg = Color.parseColor("#EDF5F0");
            dim = Color.parseColor("#8AA396");
            faint = Color.parseColor("#6E8378");
            green = Color.parseColor("#35D08F");
            red = Color.parseColor("#FF6B6B");
            lineColor = Color.parseColor("#22302A");
            warn = Color.parseColor("#E8C36B");
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette();
        build();
    }

    /** 每次回到前台重算：开关关掉或重新绑上之后，页面不能还挂着上一次的状态 */
    @Override
    protected void onResume() {
        super.onResume();
        if (built) {
            build();
        }
    }

    private boolean built;

    /** 遥控器左右键换配色：左一个、右一个循环，换完立刻生效并存下来 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int next = theme + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            if (next < 1) {
                next = THEME_NAMES.length;
            } else if (next > THEME_NAMES.length) {
                next = 1;
            }
            theme = next;
            Stats.setThemeIndex(this, next);
            palette();
            build();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void build() {
        boolean on = a11yOn();
        boolean bound = on && BootA11yService.isBound();
        boolean secure = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(bg);
        page.setPadding(dp(40), dp(22), dp(40), dp(18));
        scroll.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(header(bound && secure));
        page.addView(themeHintBlock());

        // 两列：左边状态 / 规则，右边记录 / 日志
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setBaselineAligned(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        int gap = dp(36);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.rightMargin = gap;
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cols.addView(left, leftLp);
        cols.addView(right, rightLp);
        page.addView(cols);

        // 左列
        String statusText;
        String statusHint;
        int statusColor;
        if (!on) {
            statusText = "未运行";
            statusHint = "无障碍开关是关的，到「系统设置 → 无障碍」里打开「Aerial 主页直达」";
            statusColor = red;
        } else if (!bound) {
            statusText = "未运行";
            statusHint = "无障碍开关开了，但服务没连上；把开关关掉再打开一次";
            statusColor = red;
        } else if (!secure) {
            statusText = "权限不全";
            statusHint = "缺少写设置权限，连接 Aerial 的任务可能被系统挡掉；跑一次 adb 授权即可";
            statusColor = warn;
        } else {
            statusText = "正常";
            statusHint = "按主页键弹出选择框时，点掉它回到 Aerial";
            statusColor = green;
        }
        block(left, "运行状态", statusText, statusColor, statusHint);
        String rescueAt = Stats.rescueAt(this);
        int rescueCount = Stats.rescueCount(this);
        block(left, "上次救黑屏", rescueAt.isEmpty() ? "还没发生过" : rescueAt,
                rescueAt.isEmpty() ? dim : green,
                rescueCount == 0 ? "开机没出过问题就不用管这一行"
                        : "累计 " + rescueCount + " 次");
        rules(left);
        helpBlock(left);

        // 右列
        int homeCount = Stats.homeCount(this);
        block(right, "按主页键", homeCount == 0 ? "还没记录" : "已处理 " + homeCount + " 次",
                homeCount == 0 ? dim : green,
                homeCount == 0 ? "在别的应用里按一次主页键试试" : "最近 8 次，从按下到回到 Aerial");
        String recent = Stats.homeRecent(this);
        if (!recent.isEmpty()) {
            right.addView(mono(recent, 15, fg, 4));
        }
        tailLog(right);

        TextView foot = new TextView(this);
        foot.setText("Aerial 主页直达  " + versionName() + "  ·  " + statusText);
        foot.setTextColor(faint);
        foot.setTextSize(13);
        foot.setGravity(Gravity.END);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = dp(16);
        page.addView(foot, footLp);

        setContentView(scroll);
        built = true;
    }

    /** 标题下面一行：当前配色 + 怎么切换 */
    private View themeHintBlock() {
        TextView t = new TextView(this);
        t.setText("配色：" + THEME_NAMES[theme - 1] + "   ·   按遥控器左右键切换，自动保存");
        t.setTextColor(dim);
        t.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        t.setLayoutParams(lp);
        return t;
    }

    private View header(boolean on) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(on ? green : red);
        dot.setBackground(gd);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(11), dp(11));
        dotLp.rightMargin = dp(12);
        dotLp.gravity = Gravity.CENTER_VERTICAL;
        head.addView(dot, dotLp);

        TextView title = new TextView(this);
        title.setText("Aerial 主页直达");
        title.setTextColor(fg);
        title.setTextSize(32);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        head.setLayoutParams(lp);
        return head;
    }

    /** 一行小标题 + 一行大值 + 一行说明 */
    private void block(LinearLayout parent, String label, String value, int valueColor, String sub) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(dim);
        l.setTextSize(16);
        parent.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(valueColor);
        v.setTextSize(25);
        v.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(3);
        parent.addView(v, vlp);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(faint);
        s.setTextSize(15);
        s.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        parent.addView(s, slp);

        addDivider(parent);
    }

    private void addDivider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(lineColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(14);
        parent.addView(line, lp);
    }

    private TextView mono(String text, int size, int color, int spacing) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLineSpacing(dp(spacing), 1f);
        t.setPadding(0, 0, 0, dp(12));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** 主页规则：只列两处查询实际解析到谁，不评判正常/异常 */
    private void rules(LinearLayout parent) {
        TextView l = new TextView(this);
        l.setText("主页规则");
        l.setTextColor(dim);
        l.setTextSize(16);
        parent.addView(l);

        String[] boot = query(false);
        String[] home = query(true);
        boolean bootChooser = boot[0].contains("Resolver");
        boolean homeChooser = home[0].contains("Resolver");

        TextView state = new TextView(this);
        state.setText(bootChooser && homeChooser ? "没设置双 home" : "两处规则如下");
        state.setTextColor(fg);
        state.setTextSize(25);
        state.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        parent.addView(state, slp);

        TextView bootLine = new TextView(this);
        bootLine.setText("开机查询 HOME\n" + boot[0] + (bootChooser ? "  → 会弹框" : ""));
        bootLine.setTextColor(bootChooser ? dim : green);
        bootLine.setTextSize(15);
        bootLine.setTypeface(Typeface.MONOSPACE);
        bootLine.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        parent.addView(bootLine, blp);

        TextView homeLine = new TextView(this);
        homeLine.setText("主页键查询 HOME+DEFAULT\n" + home[0] + (homeChooser ? "  → 会弹框" : ""));
        homeLine.setTextColor(homeChooser ? dim : green);
        homeLine.setTextSize(15);
        homeLine.setTypeface(Typeface.MONOSPACE);
        homeLine.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        parent.addView(homeLine, hlp);

        TextView note = new TextView(this);
        note.setText("ResolverActivity 就是系统在问「用哪个桌面」，本应用会点掉它，"
                + "所以零条记录也能用。");
        note.setTextColor(faint);
        note.setTextSize(14);
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        parent.addView(note, nlp);
    }

    /** 左下角补一块「它做什么」，把左列空出来的高度用起来 */
    private void helpBlock(LinearLayout parent) {
        addDivider(parent);
        TextView l = new TextView(this);
        l.setText("它做什么");
        l.setTextColor(dim);
        l.setTextSize(16);
        parent.addView(l);

        TextView body = new TextView(this);
        body.setText("按主页键弹出选择框 → 点掉它，回到 Aerial\n"
                + "开机真的黑屏 → 连续两次没画面就把桌面拉起来\n"
                + "不写主屏幕记录、不发返回键、不停用极米桌面");
        body.setTextColor(faint);
        body.setTextSize(15);
        body.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(body, lp);
    }

    private String[] query(boolean withDefault) {
        try {
            PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            if (withDefault) {
                i.addCategory(Intent.CATEGORY_DEFAULT);
            }
            android.content.pm.ResolveInfo ri = pm.resolveActivity(i, 0);
            if (ri == null || ri.activityInfo == null) {
                return new String[]{"读不出来"};
            }
            String pkg = ri.activityInfo.packageName;
            String cls = ri.activityInfo.name;
            if (cls != null && cls.startsWith(pkg + ".")) {
                cls = cls.substring(pkg.length());
            }
            return new String[]{pkg + cls};
        } catch (Throwable t) {
            return new String[]{"读不出来"};
        }
    }

    /** 日志尾巴：证明它确实动过手 */
    private void tailLog(LinearLayout parent) {
        TextView l = new TextView(this);
        l.setText("最近日志");
        l.setTextColor(dim);
        l.setTextSize(16);
        parent.addView(l);

        TextView body = mono(readTail(), 13, faint, 3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(body, lp);
    }

    private String readTail() {
        File f = new File(createDeviceProtectedStorageContext().getFilesDir(), "rescue.log");
        if (!f.exists()) {
            return "还没有日志";
        }
        String[] keep = new String[10];
        int n = 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                keep[n % keep.length] = line;
                n++;
            }
        } catch (Throwable t) {
            return "日志读不出来：" + t;
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
        }
        int cnt = Math.min(n, keep.length);
        StringBuilder sb = new StringBuilder();
        for (int i = n - cnt; i < n; i++) {
            String line = keep[i % keep.length];
            if (line == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            // 弹框包名太长，压成「极米弹框」，一行才看得清
            sb.append(line
                    .replace("com.xgimi.xgimiframeworkui/com.xgimi.xgimiframeworkui.resolver.ResolverActivity", "极米弹框")
                    .replace("com.xgimi.xgimiframeworkui/.resolver.ResolverActivity", "极米弹框")
                    .replace("com.xgimi.duertts/com.xgimi.duertts.MonitorService:com.sln162.bootrescue/com.sln162.bootrescue.BootA11yService:app.orchard.tv/app.orchard.tv.guard.HomeGuardService", "三项无障碍列表"));
        }
        return sb.length() == 0 ? "还没有日志" : sb.toString();
    }

    private boolean a11yOn() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }
}
