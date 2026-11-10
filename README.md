# 极米防黑屏

极米 H3S（Android 9 / pig_chivalrous / m7642）开机偶尔黑屏时的兜底工具。

装上它以后，开机黑屏会自己恢复，屏幕上还会多提示一句「电视刚才黑屏了」，按返回或者等几秒就回到桌面。

## 什么时候会黑屏

极米系统里，桌面的「首选记录」有两套查法：

- **开机时**用宽查询（只要 HOME），候选有 3 个：极米自己的桌面、Aerial、FallbackHome
- **按主页键时**用标准查询（HOME + DEFAULT），候选还是那 3 个

记录必须同时覆盖这两套候选才算数。一旦只剩一条，或者被系统降级成空壳，开机时记录直接被丢掉，系统就停在 FallbackHome 上黑着——这就是「开机偶发无限黑屏」。

细节和取证过程见 `pengchujin/aerial-tv` 的 issue #1357、#1377。

## 它怎么工作

一个无障碍服务，开机后盯着窗口。

- 连续 20 秒没有任何窗口 → 认定真黑屏 → 把 `app.orchard.tv` 拉到前台
- 拉起来 3 秒后弹一次提醒页
- 提醒页倒计时 6 秒自动回桌面，按返回也是立刻回

只有「从头到尾一次窗口都没见过」才动手。只要中途出现过任何窗口（比如正常开机 Aerial 起来了），立刻刹车，后面再也不管。所以双 home 完好的机器不会被误触发。

## 装法

1. 装 APK
2. 打开应用一次，页面会显示现在是「开着」还是「关着」
3. 关着的话，去系统设置里找到这个应用的无障碍开关打开

如果系统设置里找不到入口，用 adb：

```
settings put secure enabled_accessibility_services \
  'app.orchard.tv/.guard.HomeGuardService:com.xgimi.duertts/.MonitorService:com.sln162.bootrescue/com.sln162.bootrescue.BootA11yService'
settings put secure accessibility_enabled 1
```

服务要自己把自己写回无障碍列表，这需要 `WRITE_SECURE_SETTINGS`，装完执行一次：

```
pm grant com.sln162.bootrescue android.permission.WRITE_SECURE_SETTINGS
```

不授予也能用，只是系统把这个服务从列表里剔掉时它没法自己回来。

## 页面显示

打开应用会看到两块状态：

**第一块**是防黑屏本身：绿点是开着，红点是关着。

**第二块**是「主页规则」，三种状态：

| 显示 | 含义 |
|---|---|
| 正常（绿） | 开机直接进桌面，按主页键也不弹 |
| 只有一半（黄） | 开机能进桌面，按主页键会弹选择框 |
| 没设置（红） | 开机会弹选择框，得手动选一下 |

判断方法是查两次「系统现在会选谁」：一次只有 HOME，一次 HOME + DEFAULT。两个都指向 `app.orchard.tv` 就是正常，任一个指向选择框就是有问题。只查询，不会真的弹框。

## 它不做什么

- **不写 HOME 首选记录**。写记录要 `SET_PREFERRED_APPLICATIONS`，这是签名级权限，普通应用、无障碍服务、DeviceOwner 都拿不到，只有 shell 有
- **不接管主页键**。主页键怎么走是系统解析决定的，改不了
- **不动其他应用**。只针对 `app.orchard.tv`

## 局限

- 黑屏后要等 20 秒才动手。给足时间是为了不误伤正常开机的机器
- 记录坏掉之后，按主页键会卡几秒或者弹选择框——这个救不了，只能靠应用页面上的「主页规则」看到状态，自己去修
- Aerial 自己的签名不在仓库里，所以每次构建出来的 APK 签名都不同，覆盖安装会失败，必须先卸载

## 日志

```
adb shell run-as com.sln162.bootrescue cat /data/user_de/0/com.sln162.bootrescue/files/rescue.log
```

关心这几行：

- `bound, uptime=` 服务绑上了
- `no window (n/10), everSaw=` 在数没有窗口的次数
- `no window 10 times, pulling aerial` 认定黑屏，动手
- `boot tip shown` 提醒页弹出来了
- `aerial in front, done` 桌面回来了，收工

## 构建

代码在仓库里是普通源文件，workflow 只负责编译打包，不生成代码。

Actions → build-apk → Run workflow，跑完在 artifacts 下拿 `BootRescue-<版本>.zip`。

## 关于给 Aerial 提 PR

这套逻辑本来就应该长在 Aerial 自己身上。Aerial 自带一个无障碍守护服务，只要把「连续无窗口就拉自己」搬进去，就不用再装这个额外应用。

但这个改动会进所有 Aerial 用户的手机，风险比自用工具大得多，得先在 H3S 之外的机器上验证过才值得提。
