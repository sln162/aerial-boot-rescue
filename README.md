# ResolverKiller

一个只有无障碍服务、没有界面的极小 APK。

在“只写 wide、不写 DEFAULT”的桌面状态下，按 Home 键会弹出极米的桌面选择框
（`com.xgimi.xgimiframeworkui/.resolver.ResolverActivity`）。本服务听到这个窗口出现，
就按一次返回关掉它，然后把 Aerial 拉到前台。

不读 UI 树，不写首选记录，不碰原桌面，不影响 Aerial 自带的守护服务。

## 实测确认的现场数据（2026-11-09 只读采集）

- 选择框组件：`com.xgimi.xgimiframeworkui/.resolver.ResolverActivity`
- 现有无障碍服务：`com.xgimi.duertts/.MonitorService`、`app.orchard.tv/.guard.HomeGuardService`
- `accessibility_enabled = 1`

## 预演时序（只写 wide 状态，按 Home 键）

| 时刻 | 发生什么 |
|---|---|
| t=0 | 按 Home |
| t≈100ms | 系统解析 HOME 意图，没有 DEFAULT 记录 → 启动 ResolverActivity |
| t≈150ms | ResolverActivity 获得焦点 → 发出 TYPE_WINDOW_STATE_CHANGED |
| t≈160ms | 本服务命中，执行 performGlobalAction(GLOBAL_ACTION_BACK) |
| t≈200ms | 选择框关闭 |
| t≈250ms | startActivity(app.orchard.tv/.MainActivity) |
| t≈400–600ms | Aerial 回到前台 |

## 已知边界

1. 黑屏场景不管（选择框没有焦点窗口时收不到事件），兜底仍是脚本或手动按 Home。
2. 第三方 app 里“什么都不弹、遥控器无响应”那一种不管。
3. 需要用户手动启用一次无障碍服务。
4. `canRetrieveWindowContent=false`，权限最小。

## 构建

GitHub Actions 在 push 到 main 后自动构建，产物在 Actions 页面的 `ResolverKiller-debug` artifact。

## 安装与启用

```powershell
adb install -r app-debug.apk
adb shell settings put secure enabled_accessibility_services "com.xgimi.duertts/com.xgimi.duertts.MonitorService:app.orchard.tv/app.orchard.tv.guard.HomeGuardService:com.jmk92.resolverkiller/com.jmk92.resolverkiller.ResolverKillerService"
```

或在 设置 → 无障碍 里打开 “Resolver Killer”。
