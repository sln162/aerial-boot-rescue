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
            title.setText(on ? "桌面守护运行中" : "桌面守护未启用");
        }
        title.setTextColor(Color.parseColor("#EDF5F0"));
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        if (bootTip) {
            sub.setText("别急，已经自动恢复，可以继续看");
        } else {
            sub.setText(on ? "电视黑屏时，它会自己把桌面拉回来" : "请到 系统设置 → 无障碍 → BootRescue → 启用");
        }
        sub.setTextColor(Color.parseColor("#8AA396"));
        sub.setTextSize(17);
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

        TextView tips = new TextView(this);
        if (bootTip) {
            tips.setText("按一下遥控器「返回」\n就能回到桌面继续看\n\n"
                    + "这次是它自己把画面拉回来的\n"
                    + "守护：" + (on ? "正在运行" : "没有启用"));
        } else {
            tips.setText("如果电视黑屏\n"
                    + "遥控器按一次「返回」\n"
                    + "等 10 秒，桌面会出来\n\n"
                    + "如果一直出不来\n"
                    + "联系装电视的家人，别乱按");
        }
        tips.setTextColor(Color.parseColor("#A9BCB2"));
        tips.setTextSize(17);
        tips.setLineSpacing(dp(4), 1f);
        tips.setGravity(Gravity.CENTER);
        root.addView(tips);

        // 底部小字
        TextView foot = new TextView(this);
        foot.setText("BootRescue 2.6  ·  " + (bootTip ? "刚自动恢复过" : (on ? "守护开着" : "守护关着")));
        foot.setTextColor(Color.parseColor("#41544B"));
        foot.setTextSize(12);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = dp(44);
        footLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(foot, footLp);

        setContentView(root);
    }
}
