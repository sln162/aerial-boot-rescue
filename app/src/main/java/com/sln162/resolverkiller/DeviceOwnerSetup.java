package com.sln162.resolverkiller;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class DeviceOwnerSetup {

    private static final String AERIAL = "app.orchard.tv/app.orchard.tv.MainActivity";

    public static boolean isOwner(Context c) {
        try {
            DevicePolicyManager d = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return d != null && d.isDeviceOwnerApp(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static void ensure(Context c) {
        if (!isOwner(c)) return;
        try {
            DevicePolicyManager d = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(c, AdminReceiver.class);
            ComponentName aerial = ComponentName.unflattenFromString(AERIAL);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            d.addPersistentPreferredActivity(admin, f, aerial);
        } catch (Throwable t) {
        }
    }
}
