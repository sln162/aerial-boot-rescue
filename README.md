# ResolverKiller

一个只有无障碍服务、没有界面的极小 APK，给极米 H3S + Aerial 桌面用。

解决的是按 Home 键的两个毛病：

1. 在第三方 App 里按 Home，卡五秒才回桌面 —— 现在 1.2–1.5 秒
2. 桌面自己在前台时按 Home，会闪一下上一个 App 的画面 —— 现在压到一两帧

做法是监听 HOME 键的按下事件，在按下的那一刻就把 Aerial 拉到前台，抢在系统 HOME 解析流程之前。系统随后还是会弹出极米的桌面选择框，服务再按一次返回把它关掉，然后重新拉一次 Aerial。

不读 UI 树，不写首选记录，不碰原桌面，不影响 Aerial 自带的守护服务。

## 实测数据

H3S，真实按键 120 ms，各 5 次取中位：

| 场景 | 无服务 | 有服务 |
|---|---|---|
| 桌面按 Home | 798 ms | 1200 ms |
| 第三方 App 按 Home | 1458 ms | 1149 ms |
| 长按 Home | 1.2 秒（等抬起才进最近任务） | 0.65 秒（按下 500 ms 就进） |

第三方 App 那 5 秒的成因是：系统把请求交给 HOME 解析流程，最后的兜底是 Aerial 自带的守护服务，它 4.5 秒才动手。

## 时序

| 时刻 | 发生什么 |
|---|---|
| t=0 | 按下 Home，服务立刻发 startActivity |
| t≈50–90 ms | Aerial 的启动请求已发出 |
| t≈150 ms | 系统把上一个任务提上来（闪的就是这一下） |
| t≈250–400 ms | 极米桌面选择框弹出 |
| t≈400 ms | 服务按返回键关掉选择框 |
| t≈500 ms | 再拉一次 Aerial |
| t≈1.2–1.5 s | Aerial 稳定在前台 |

拉 Aerial 必须抢在按下后的几十毫秒内发出。挪到抬起时再发（哪怕只延后 150 ms），请求就会被系统的 HOME 解析流程吃掉，退化成 4.5 秒兜底。

## 保活

极米语音会周期性把本服务从无障碍列表里剔掉。服务会自己把自己写回去，不需要电脑，也不需要 root。

前提是拿到 `WRITE_SECURE_SETTINGS`：

```powershell
adb shell pm grant com.sln162.resolverkiller android.permission.WRITE_SECURE_SETTINGS
```

这个权限在系统里的保护级别必须带 `development`（极米的定义是 `signature|privileged|development`），并且本 APK 是 debuggable，才允许 `pm grant` 授予。标准 AOSP 只有 `signature|privileged`，授不了。

三道防线：

1. 监听 `enabled_accessibility_services`，被改就立刻写回（实测 2 秒内恢复）
2. 监听开机广播，开机后 5 / 20 / 60 / 150 秒各查一次
3. JobScheduler 周期任务，15 分钟一次，`setPersisted(true)` 跨重启保留

15 分钟是 Android 9 允许的最小周期。更短只能用 AlarmManager，而闹钟在待机时会被推迟，反而更不准。一次执行只读一个设置字符串，CPU 几十毫秒。

## 适用范围

这个 APK 只针对 **极米固件的选择框 + Aerial 桌面** 这一组合：

- 换品牌固件（小米、海信等）：选择框的包名不同，需要改 `ResolverKillerService.java` 里的 `TARGET_CLASS`
- 换桌面 App（不用 Aerial）：需要改 `AERIAL_PKG` / `AERIAL_ACT`

同是极米设备、用 Aerial 桌面的用户，装上就能直接用。

## 实测现场数据

- 选择框组件：`com.xgimi.xgimiframeworkui/.resolver.ResolverActivity`
- Aerial：`app.orchard.tv/.MainActivity`，`launchMode=singleTask`，`clearTaskOnLaunch`
- 目标 App 示例：`com.fongmi.android.tv/com.fongmi.android.tv.ui.activity.HomeActivity`
- 极米语音：`com.xgimi.duertts/com.xgimi.duertts.MonitorService`

## 已知边界

1. 黑屏场景不管（选择框没有焦点窗口时收不到事件），兜底仍是脚本或手动按 Home。
2. 第三方 app 里“什么都不弹、遥控器无响应”那一种不管。
3. 需要先手动启用一次无障碍服务，之后靠自愈维持。
4. 卸载重装会丢掉 `WRITE_SECURE_SETTINGS` 授权，必须重新 `pm grant`。

## 构建

手动触发 Actions（`workflow_dispatch`），填版本号，产物是 `ResolverKiller-<版本>.apk`。

## 安装与启用

```powershell
adb uninstall com.sln162.resolverkiller
adb install ResolverKiller-v14.apk
adb shell settings put secure enabled_accessibility_services "app.orchard.tv/app.orchard.tv.guard.HomeGuardService:com.xgimi.duertts/com.xgimi.duertts.MonitorService:com.sln162.resolverkiller/com.sln162.resolverkiller.ResolverKillerService"
adb shell pm grant com.sln162.resolverkiller android.permission.WRITE_SECURE_SETTINGS
```

覆盖安装同包名不同签名的包会静默失败，所以必须卸载再装。装完要重新授权，否则自愈失效。
