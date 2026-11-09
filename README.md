# BootRescue

极米 H3S（Android 9 / pig_chivalrous / m7642）开机黑屏兜底工具。

## 解决什么问题

Aerial 桌面在某些重启或断电后不会自己回到前台，屏幕停在 FallbackHome 上黑着。原因不在 Aerial 自身，而在系统 HOME 首选记录在开机时的候选集差异（详见 pengchujin/aerial-tv 的 issue #1357 和 #1377）。

Aerial 的 `BootReceiver` 在 `LOCKED_BOOT_COMPLETED` 分支里直接 return，所以在系统停在「用户已启动、BOOT_COMPLETED 迟迟不来」的状态时，它自己不会拉起界面。

这个 apk 只做一件事：收到开机广播后，把 `app.orchard.tv` 拉到前台。

## 它不做什么

- 不写 HOME 首选记录，不动系统状态
- 只要 `RECEIVE_BOOT_COMPLETED`，不要别的权限
- 不注册无障碍服务
- 界面是一个静态页，唯一用途是在 Aerial 里点一次清掉 `stopped` 状态

## 组件

| 组件 | 说明 |
|---|---|
| `BootRescueReceiver` | `directBootAware=true`，同时收 `LOCKED_BOOT_COMPLETED` 与 `BOOT_COMPLETED` |
| `MainActivity` | 带 LAUNCHER 图标，只为清 `stopped`；页面显示 uptime 和目标包名 |

## 判断逻辑

- `SystemClock.elapsedRealtime() > 120000` 直接跳过：离开机太远，不是开机场景
- 同一进程只做一次（`sDone`）
- 收到广播后等 1.8 秒，让系统先把 FallbackHome 摆好，再 `startActivity`
- 目标：先 `getLaunchIntentForPackage("app.orchard.tv")`，取不到就显式 `ComponentName(app.orchard.tv, app.orchard.tv.MainActivity)`
- flag：`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`

## 日志

写在 device-protected 存储，锁定阶段也能写：

```
/data/user_de/0/com.sln162.bootrescue/files/rescue.log
```

读取：

```
adb shell run-as com.sln162.bootrescue cat /data/user_de/0/com.sln162.bootrescue/files/rescue.log
```

日志能区分三种结果：广播没到（无 `onReceive`）、到了但 `startActivity` 抛异常、以及 `startActivity ok` 但屏幕仍黑。

## 构建

`.github/workflows/build.yml` 的 `Reset project sources` 步骤用 heredoc 生成全部源码，所以改代码只改这一个 workflow 文件就够了。

Actions → build-apk → Run workflow，跑完在 artifacts 下拿 `BootRescue-<version>.zip`。

## 这是取证工具，不是长期方案

它要回答的是一个具体问题：**从 `LOCKED_BOOT_COMPLETED` 的广播接收器里发 `startActivity`，能不能真的把界面拉起来。**

`am start` 是 shell 身份（系统级），广播接收器是应用身份，两者路径不同。没有实测过就不能当结论。

这条实测只有给 Aerial 提 PR 才有长期价值：Aerial 内部改一行就能做同样的事，不需要再装一个 app。
