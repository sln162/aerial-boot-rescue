# Aerial 主页直达

极米电视（Android 9 那一代固件）把 Aerial 当桌面用时，按主页键会先弹出系统的「用哪个桌面」选择框，卡几秒才回来，有时候直接卡住。

这个无障碍服务把框点掉，同时把 Aerial 送到前台，**从按下到回到桌面 0.4 秒**。开机黑屏那一下也兼顾兜一次。

不写主屏幕记录、不发返回键、不停用极米原桌面、不需要设置双 home。

![界面](docs/ui.png)

## 解决什么

- **在别的 app 里按主页键，弹框卡几秒**：框一出现就点掉，接着直接拉起 Aerial
- **双 home 规则会随机丢**：不靠记录，首选记录是零条也照常工作
- **开机偶尔整屏全黑**：开机流程走完后连续两次看不到界面才兜底，正常开机不打扰

## 支持范围

判据不是机型，是**系统弹的是不是极米那个自己的 resolver**（`com.xgimi.xgimiframeworkui...resolver.ResolverActivity`）：只要固件用的是它，跟机型无关，极米这一代固件基本都是同一个。

- **Android 9 极米：实测可用**（H3S 上跑了 34 次按主页键、一次真重启）
- **Android 11 及更新的极米：未实测**。机制是一样的（系统 resolver + 无障碍点节点），预计可用；但这个 resolver 的按钮文案、节点层级可能变，代码里认的是文字「仅此一次」和候选行，如果写法不一样就不动手（会退化成没有这个应用，不会乱点别的窗口）
- 非极米设备：AOSP 的 `ResolverActivity` 也留了分支，未实测


## 它有多快

20 连按 20/20：

```
+0~1ms      click row Aerial=true
+106~126ms  click 仅此一次 (round 1)
+13ms       pull: start aerial right after click 仅此一次
+399~448ms  check#1 front=app.orchard.tv
```

弹框一出现就点中 Aerial 那一行（按钮这时才亮），亮了立刻点「仅此一次」，同一时刻自己把 Aerial 拉起来，抢在弹框默认候选（极米桌面）画出来之前。屏幕上不会看到极米桌面闪一下。

事件偶尔会漏，另有 1 秒一次的巡检补一次，实测 34 次里 2 次走这条路，同样 0.5 秒内到站。

## 装法

1. 从 [Releases](https://github.com/sln162/aerial-boot-rescue/releases) 下 APK，覆盖安装失败就先卸载旧的（调试签名）
2. adb 三条：

```
pm grant com.sln162.bootrescue android.permission.WRITE_SECURE_SETTINGS
settings put secure enabled_accessibility_services \
  'com.xgimi.duertts/.MonitorService:app.orchard.tv/.guard.HomeGuardService:com.sln162.bootrescue/com.sln162.bootrescue.BootA11yService'
settings put secure accessibility_enabled 1
```

3. 打开一次应用，「运行状态」显示绿色「正常」就成了

`pm grant` 不是可选项：极米在解锁那一刻会重写无障碍列表，把第三方服务剔掉，没有这个权限服务写不回去。

## 界面看什么

| 区块 | 内容 |
|---|---|
| 运行状态 | 正常 / 未运行 / 权限不全 |
| 上次救黑屏 | 最近一次兜底的时间；没发生就是「还没发生过」，下面是累计次数 |
| 主页规则 | 开机查询、主页键查询各自解析到谁，两处都是 ResolverActivity 就写「没设置双 home」 |
| 按主页键 | 累计次数；最近 8 次带时间、`+Nms`、怎么处理的 |
| 最近日志 | 日志末 10 行，长包名压成「极米弹框」「三项无障碍列表」 |

开机不再弹任何页面，事后打开应用就能看到处理了几次。

## 它不做什么

- **不写 HOME 首选记录**，**不点「始终」**。这个弹框里「始终」写进的是默认选中那一行（极米桌面）；点候选行改不了 `selected`，`ACTION_SELECT` 能改但会把按钮锁死
- **不发返回键**。返回键永远发给当前焦点窗口，在第三方 app 里按主页键时会落到那个 app 身上，实测卡几秒
- **不冻结、不禁用极米自带桌面**，不动系统组件

## 局限

- 选「仅此一次」之后弹框会启动默认那一行（极米桌面），我们紧接着自己拉起 Aerial 盖掉；这一步靠无障碍服务的后台启动豁免
- 个别时候到站 0.6 秒
- 弹框被 Aerial 完整盖住后节点就从 `getWindows()` 里消失，所以动作放在事件里 0 延迟执行
- 只在 Android 9 极米固件上跑过，更高版本未实测（见「支持范围」）

## 日志

```
adb shell run-as com.sln162.bootrescue cat /data/user_de/0/com.sln162.bootrescue/files/rescue.log
```

## 构建

改动版本号在 `app/build.gradle` 的 `versionCode` / `versionName`，本地 `python build.py` 或 Actions 的 `build-apk` 打包。
