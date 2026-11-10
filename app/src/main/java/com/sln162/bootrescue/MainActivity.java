package com.sln162.bootrescue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean bootTip = getIntent() != null
                && getIntent().getBooleanExtra("boot_tip", false);

        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.contains(getPackageName());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0B1210"));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 状态圆点
        View dot = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(on ? Color.parseColor("#35D08F") : Color.parseColor("#FF6B6B"));
        dot.setBackground(gd);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotLp.bottomMargin = dp(28);
        dotLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(dot, dotLp);

        TextView title = new TextView(this);
        if (bootTip) {
            title.setText("电视刚才黑屏了");
        } else {
            title.setText(on ? "防黑屏开着" : "防黑屏关着");
        }        title.setTextColor(Color.parseColor("#EDF5F0"));
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        final TextView sub = new TextView(this);
        if (bootTip) {
            sub.setText("6 秒后自动回桌面");
        } else {
            sub.setText(on ? "电视黑屏的时候，它会自己把桌面拉回来" : "请到「系统设置」里把它打开");
        }
        sub.setTextColor(Color.parseColor("#8AA396"));
        sub.setTextSize(bootTip ? 22 : 17);
        sub.setTypeface(null, bootTip ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(14);
        subLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(sub, subLp);

        // 分隔线
        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#22302A"));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(dp(260), dp(1));
        lineLp.topMargin = dp(40);
        lineLp.bottomMargin = dp(40);
        lineLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(line, lineLp);

        // 主页规则状态（只在手动打开时显示）
        if (!bootTip) {
            String[] rule = homeRule();
            TextView ruleTitle = new TextView(this);
            ruleTitle.setText("主页规则：" + rule[0]);
            ruleTitle.setTextColor(Color.parseColor(rule[2]));
            ruleTitle.setTextSize(18);
            ruleTitle.setGravity(Gravity.CENTER);
            root.addView(ruleTitle);

            TextView ruleSub = new TextView(this);
            ruleSub.setText(rule[1]);
            ruleSub.setTextColor(Color.parseColor("#8AA396"));
            ruleSub.setTextSize(15);
            ruleSub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ruleLp.topMargin = dp(8);
            ruleLp.bottomMargin = dp(34);
            ruleLp.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(ruleSub, ruleLp);
        }

        TextView tips = new TextView(this);
        if (bootTip) {
            tips.setText("按一下「返回」立刻回去");
        } else {
            tips.setText("平时不用管它\n"
                    + "电视黑屏的时候\n"
                    + "它会自己把桌面带回来\n\n"
                    + "真卡住了，遥控器按一次「返回」也行");
        }
        tips.setTextColor(Color.parseColor("#A9BCB2"));
        tips.setTextSize(17);
        tips.setLineSpacing(dp(4), 1f);
        tips.setGravity(Gravity.CENTER);
        root.addView(tips);

        // 底部小字
        TextView foot = new TextView(this);
        foot.setText("极米防黑屏 1.3.2  ·  " + (bootTip ? "马上回桌面" : (on ? "开着" : "关着")));
        foot.setTextColor(Color.parseColor("#7A8F84"));
        foot.setTextSize(14);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = dp(30);
        footLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(foot, footLp);

        setContentView(root);

        // 倒计时：每秒刷新一次，到 0 自己回桌面
        if (bootTip) {
            final int[] left = {6};
            final android.os.Handler h = new android.os.Handler(getMainLooper());
            final Runnable r = new Runnable() {
                @Override
                public void run() {
                    left[0]--;
                    if (left[0] <= 0) {
                        goBackToAerial();
                        return;
                    }
                    sub.setText(left[0] + " 秒后自动回桌面");
                    h.postDelayed(this, 1000);
                }
            };
            h.postDelayed(r, 1000);
        }
    }

    // 查两次"系统现在会选谁"：一次按开机的查法，一次按主页键的查法
    private String[] homeRule() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            String boot = resolveHome(pm, false);
            String home = resolveHome(pm, true);
            if (boot == null || home == null) {
                return new String[]{"读不出来", "先不用管它", "#8AA396"};
            }
            boolean bootAerial = boot.startsWith("app.orchard.tv");
            boolean homeAerial = home.startsWith("app.orchard.tv");
            boolean bootPicker = boot.contains("Resolver");
            boolean homePicker = home.contains("Resolver");

            if (bootAerial && homeAerial) {
                return new String[]{"正常", "开机直接进桌面，按主页键也不弹", "#35D08F"};
            }
            if (bootAerial && homePicker) {
                return new String[]{"只有一半", "开机能进桌面，按主页键会弹选择框", "#FFB86B"};
            }
            if (bootPicker) {
                return new String[]{"没设置", "开机会弹选择框，要手动选一下", "#FF6B6B"};
            }
            return new String[]{"不太对", "可能进不去桌面，需要处理一下", "#FF6B6B"};
        } catch (Throwable t) {
            return new String[]{"读不出来", "先不用管它", "#8AA396"};
        }
    }

    private String resolveHome(android.content.pm.PackageManager pm, boolean withDefault) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        if (withDefault) {
            i.addCategory(Intent.CATEGORY_DEFAULT);
        }
        android.content.pm.ResolveInfo ri = pm.resolveActivity(i, 0);
        if (ri == null || ri.activityInfo == null) {
            return null;
        }
        return ri.activityInfo.packageName + "/" + ri.activityInfo.name;
    }

    private void goBackToAerial() {
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(
                    "app.orchard.tv", "app.orchard.tv.MainActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Throwable ignored) {
        }
        finish();
    }

    // 按返回直接回 Aerial，不要落到开机时那个“打开方式”选择框
    @Override
    public void onBackPressed() {
        if (getIntent() != null && getIntent().getBooleanExtra("boot_tip", false)) {
            goBackToAerial();
            return;
        }
        super.onBackPressed();
    }
}
