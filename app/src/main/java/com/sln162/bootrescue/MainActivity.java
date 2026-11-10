package com.sln162.bootrescue;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.contains(getPackageName());
        String list = enabled == null ? "null" : enabled;

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER);
        ll.setBackgroundColor(Color.BLACK);
        ll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView t = new TextView(this);
        t.setText("BootRescue 2.2\n\n"
                + "无障碍服务：" + (on ? "已启用" : "未启用") + "\n"
                + "uptime: " + (SystemClock.elapsedRealtime() / 1000) + " s\n"
                + "target: app.orchard.tv\n\n"
                + "当前列表：\n" + list + "\n\n"
                + "开机绑定后会自动补写自己");
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        ll.addView(t);

        setContentView(ll);
    }
}
