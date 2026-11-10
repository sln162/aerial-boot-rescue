# 极米防黑屏

极米电视（H3S / H5 / 神灯 L1 这一代，Android 9）装了第三方桌面 Aerial 之后，开机偶尔会**整块屏幕全黑**，什么都看不到，状态栏也没有，只有 adb 还能连上。必须手动按遥控或者拔电重来。

这是个无障碍服务，开机那几分钟盯着窗口。如果系统开机流程已经走完、却连续两次看不到任何可用界面，就把 Aerial 拉起来，屏幕上给一句「电视刚才黑屏了」的提示，然后回到桌面。

最新 APK 在 [Releases](https://github.com/sln162/aerial-boot-rescue/releases) 页面下载。

## 为什么会黑屏

极米系统查桌面「首选记录」有两套查法：

- **开机时**用宽查询（只要 HOME），候选有三个：极米自己的桌面、Aerial、FallbackHome
- **按主页键时**用标准查询（HOME + DEFAULT），候选还是这三个

记录得同时覆盖这两套候选才算数。只剩一条、或者被系统降级成空壳，开机时记录就被固件丢掉，系统解析落到 `com.xgimi.xgimiframeworkui/.resolver.ResolverActivity`，但选择框又没起来——结果就是一个窗口都没有，屏幕全黑。

这是 Aerial 自己的 issue [#1357](https://github.com/pengchujin/aerial-tv/issues/1357)（H5）、[#1377](https://github.com/pengchujin/aerial-tv/issues/1377)（H3S）记录的现象，取证过程在那边。

配套的修法：Aerial 侧已经提了 PR [#1449](https://github.com/pengchujin/aerial-tv/pull/1449)，把这个兜底搬进 Aerial 自己的守护服务。作者合并发布之后，就不用再装这个额外应用。

## 它怎么工作

一个无障碍服务，开机后每 1.5 秒看一眼当前前台是什么。

判据全部来自系统状态，**没有任何「等多少秒」的写死时间**：

- `init.svc.bootanim == "stopped"` 或 `sys.boot_completed == "1"` → 认为系统开机流程走完了
- 走完之后，前台还是空的，或者停在 `FallbackHome` / `ResolverActivity`
- **连续两次**都是这样 → 认定真黑屏 → 把 `app.orchard.tv` 拉到前台

只要中途看到过一次 Aerial 在前台，就立刻收工，后面再也不管。所以正常开机的机器不会被误触发。

还有两道刹车：

- 只在开机 10 分钟内动作，超过就永久收工（日常重启服务时 uptime 早超了，不会乱动）
- 从没在开机过程中看到 Aerial 之前，不会动手

## 装法

1. 从 [Releases](https://github.com/sln162/aerial-boot-rescue/releases) 下载最新 APK，装到电视上
2. 打开应用一次，页面上会显示防黑屏是「开着」还是「关着」
3. 关着的话，去系统设置里把这个应用的无障碍开关打开（极米路径一般是 设置 → 辅助功能 → 无障碍）

如果系统设置里找不到入口，用 adb：

```
pm grant com.sln162.bootrescue android.permission.WRITE_SECURE_SETTINGS
settings put secure enabled_accessibility_services \
  'com.xgimi.duertts/.MonitorService:app.orchard.tv/.guard.HomeGuardService:com.sln162.bootrescue/com.sln162.bootrescue.BootA11yService'
settings put secure accessibility_enabled 1
```

`pm grant` 那条只影响一件事：系统把这个服务从无障碍列表里剔掉时，它自己能不能爬回来。不授予也能用，只是被剔掉后需要手动再开一次。

**装完先卸载旧的再装新的**，不要覆盖安装：调试签名的 APK 每次构建签名可能不同，覆盖会失败。

## 页面显示

打开应用有两块状态。

**第一块是防黑屏本身**：绿点是开着，红点是关着。

**第二块是「主页规则」**，三种状态：

| 显示 | 含义 |
|---|---|
| 正常（绿） | 开机直接进桌面，按主页键也不弹 |
| 只有一半（黄） | 开机能进桌面，按主页键会弹选择框 |
| 没设置（红） | 开机会弹选择框，得手动选一下 |

判断方法是查两次「系统现在会选谁」：一次只有 HOME，一次 HOME + DEFAULT。两个都指向 `app.orchard.tv` 就是正常，任一个指向选择框就是有问题。只查询，不会真的弹框。

## 它不做什么

- **不写 HOME 首选记录**。写记录要 `SET_PREFERRED_APPLICATIONS` 签名级权限，普通应用、无障碍服务、DeviceOwner 都拿不到，只有 shell 有
- **不接管主页键**。主页键交给系统解析，这个改不了
- **不冻结、不禁用极米自带的桌面**。只把 Aerial 拉起来，不动任何系统组件
- **不动其他应用**，只针对 `app.orchard.tv`

## 局限

- 黑屏后要等「开机流程走完 + 连续两次读数」，实测白屏认证通过后大约 10 秒左右恢复，这个时间跟机器快慢有关
- 记录坏掉之后，按主页键还是会弹选择框或者卡几秒。这个救不了，只能靠页面上「主页规则」看到状态，自己按下面两种方式修
- 想开机和按主页键都不弹框，要写双 home（一条 `set=3` 不带 DEFAULT + 一条 `set=2` 带 DEFAULT，见 Aerial 的 issue #942）。双 home 也可能被固件随机丢掉，丢掉之后还是会黑，那就靠这个应用拉起来
- 极米系统在解锁时会重写无障碍列表，有可能把本应用剔出去。授予 `WRITE_SECURE_SETTINGS` 之后它会自己写回来，没授予的话需要手动再开

## 日志

```
adb shell run-as com.sln162.bootrescue cat /data/user_de/0/com.sln162.bootrescue/files/rescue.log
```

关心的几行：

| 日志 | 含义 |
|---|---|
| `bound, uptime=` | 服务绑上了 |
| `still booting (bootanim=...)` | 系统还在开机（白屏、动画、认证），等待，不动手 |
| `boot finished but no usable window (1/2, front=...)` | 开机流程走完，但没看到可用界面，开始计数 |
| `boot finished and still nothing ... pulling aerial` | 连续两次都空，拉 Aerial |
| `boot tip shown` | 提醒页弹出来了 |
| `aerial in front, done` | 桌面回来了，收工 |

## 构建

代码就是仓库里的普通源文件，workflow 只做编译打包，不生成代码。

Actions → `build-apk` → Run workflow，跑完在 artifacts 下拿 `BootRescue-<版本>.zip`。版本号改 `app/build.gradle` 里的 `versionCode` / `versionName`。
