#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""H3S 双 HOME 记录修复：只改记录，不动画面

用法：
      python fix-aerial-home.py            # 检查 + 坏了才修，全程不切任何界面
      python fix-aerial-home.py --rescue   # 黑屏救援用：修完再把 Aerial 拉到前台

行为：
  1. 等 adb 连上
  2. 读 preferred-xml
  3. 记录健康 → 什么都不做，直接退出
  4. 记录坏了 / set 被清空 / always=false → 清空重写双 HOME 记录
  5. 全程不发返回键、不 am start；只有 --rescue 才拉起 Aerial
"""
import os
import re
import shutil
import subprocess
import sys
import time

SERIAL = os.environ.get("TV_SERIAL", "192.168.31.65:5555")
TXN_REPLACE = 70
TXN_CLEAR = 71

MAIN = "android.intent.action.MAIN"
HOME = "android.intent.category.HOME"
DEFAULT = "android.intent.category.DEFAULT"
AERIAL = ("app.orchard.tv", "app.orchard.tv.MainActivity")
AERIAL_ACT = "app.orchard.tv/.MainActivity"
STOCK_PKG = "com.xgimi.home"
FALLBACK_PKG = "com.android.newsettings"


def adb(*args, timeout=40):
    exe = shutil.which("adb") or r"C:\Users\jmk92\platform-tools\adb.exe"
    r = subprocess.run([exe, "-s", SERIAL, *args],
                       capture_output=True, text=True, timeout=timeout)
    if r.returncode:
        raise RuntimeError("adb %s 失败：%s%s" % (" ".join(args), r.stdout, r.stderr))
    return r.stdout


def wait_device(max_wait=150):
    exe = shutil.which("adb") or r"C:\Users\jmk92\platform-tools\adb.exe"
    deadline = time.time() + max_wait
    printed = False
    while time.time() < deadline:
        try:
            subprocess.run([exe, "connect", SERIAL], capture_output=True, text=True, timeout=20)
            r = subprocess.run([exe, "devices"], capture_output=True, text=True, timeout=20)
            for line in r.stdout.splitlines():
                if SERIAL in line and "device" in line and "offline" not in line:
                    return True
        except Exception:
            pass
        if not printed:
            print("等待设备上线…（黑屏时 adb 可能要几十秒到几分钟）")
            printed = True
        time.sleep(10)
    return False


def parse_items(xml):
    items = []
    for m in re.finditer(r"<item\s+([^>]+)>(.*?)</item>", xml, re.S):
        attrs, body = m.group(1), m.group(2)
        d = {}
        for k in ("name", "always", "set", "match"):
            mm = re.search(k + r'="([^"]*)"', attrs)
            d[k] = mm.group(1) if mm else ""
        d["default"] = DEFAULT in body
        items.append(d)
    return items


def healthy_reason(items):
    # 顺序不重要：宽查询只命中不带 DEFAULT 那条，标准查询只命中带 DEFAULT 那条，
    # 两条各管一种查询，只要都在、都没被作废，就是健康。
    if len(items) != 2:
        return False, "记录不是 2 条（当前 %d 条）" % len(items)
    for it in items:
        if "app.orchard.tv" not in it["name"]:
            return False, "记录没有指向 Aerial"
        if it["always"] != "true":
            return False, "有记录被系统置成 always=false（作废）"
        if it["set"] in ("0", ""):
            return False, "有记录的 set 被清空（作废）"
    if sum(1 for it in items if it["default"]) != 1:
        return False, "带 DEFAULT 与不带 DEFAULT 的记录不是各一条"
    return True, "健康"


def query_candidates():
    out = adb("shell", "pm", "query-activities", "-a", MAIN, "-c", HOME)
    found = []
    for m in re.finditer(r"ActivityInfo:\s*\n\s*name=([\w.]+)\s*\n\s*packageName=([\w.]+)", out):
        name, pkg = m.group(1), m.group(2)
        if (pkg, name) not in found:
            found.append((pkg, name))
    return found


def pick(cands, pkg):
    hit = [p for p in cands if p[0] == pkg]
    if not hit:
        raise RuntimeError("候选集里找不到 %s" % pkg)
    if len(hit) > 1:
        raise RuntimeError("%s 有多个 HOME 组件：%s" % (pkg, hit))
    return hit[0]


def binder_replace(categories, candidates, priority):
    payload = []

    def i(v):
        payload.extend(["i32", str(v)])

    def s(v):
        payload.extend(["s16", v])

    i(1)
    i(1)
    s(MAIN)
    i(1)
    i(len(categories))
    for c in categories:
        s(c)
    for _ in range(5):
        i(0)
    i(priority)
    for _ in range(4):
        i(0)
    i(0x100000)
    i(len(candidates))
    for pkg, act in candidates:
        i(1)
        s(pkg)
        s(act)
    i(1)
    s(AERIAL[0])
    s(AERIAL[1])
    i(0)

    out = adb("shell", "service", "call", "package", str(TXN_REPLACE), *payload)
    if not re.search(r"Parcel\(\s*00000000\b", out):
        raise RuntimeError("service call 返回异常：%s" % out)


def pull_up_aerial():
    # 黑屏时可能压着 ResolverActivity，先按返回
    try:
        adb("shell", "input", "keyevent", "KEYCODE_BACK", timeout=20)
        time.sleep(1)
    except Exception:
        pass
    adb("shell", "am", "start", "-n", AERIAL_ACT)


def main():
    rescue = "--rescue" in sys.argv
    print("=" * 46)
    print("H3S 双 HOME 记录检查" + ("（救援模式）" if rescue else "（只检查记录，不动画面）"))
    print("=" * 46)

    if not wait_device():
        print("\n[失败] adb 连不上设备。")
        print("建议：按遥控器电源键关机；不行就长按机身电源键断电，再开机重试。")
        return 1

    print("[ok] adb 已连接")
    try:
        xml = adb("shell", "dumpsys", "package", "preferred-xml", "--full")
    except Exception as e:
        print("[失败] 读 preferred-xml 出错：%s" % e)
        return 1

    items = parse_items(xml)
    for idx, it in enumerate(items, 1):
        print("  记录%d：%s  set=%s  always=%s%s"
              % (idx, it["name"], it["set"], it["always"],
                 "  [带DEFAULT]" if it["default"] else ""))
    ok, reason = healthy_reason(items)
    print("判断：%s" % reason)

    if ok:
        print("-> 记录健康，不改动任何设置，也不切换界面")
        print("\n完成。")
        return 0
    else:
        # 只有确实坏了才动
        try:
            print("-> 记录异常，开始重写双 HOME 规则…")
            cands = query_candidates()
            stock = pick(cands, STOCK_PKG)
            try:
                fallback = pick(cands, FALLBACK_PKG)
            except RuntimeError:
                fallback = None
                print("-> FallbackHome 不在候选里，按 2 候选写入")
            wide = [stock, AERIAL, fallback] if fallback else [stock, AERIAL]

            adb("shell", "service", "call", "package", str(TXN_CLEAR), "s16", AERIAL[0])
            binder_replace([HOME], wide, 1)
            binder_replace([HOME, DEFAULT], [stock, AERIAL], 0)

            xml2 = adb("shell", "dumpsys", "package", "preferred-xml", "--full")
            items2 = parse_items(xml2)
            ok2, reason2 = healthy_reason(items2)
            print("-> 重写完成，复查：%s" % reason2)
            if not ok2:
                print("[警告] 重写后仍不健康，可能固件行为不同，请保留现场排查。")
                return 1
        except Exception as e:
            print("[失败] 修复过程出错：%s" % e)
            print("记录可能未改动或只改了一半，请再运行一次本脚本复查。")
            return 1

    # 默认绝不切界面；只有黑屏救援才把 Aerial 拉到前台
    if rescue:
        print("-> 救援模式：拉起 Aerial…")
        try:
            pull_up_aerial()
            print("[ok] 已发送启动命令")
        except Exception as e:
            print("[失败] 拉起 Aerial 出错：%s" % e)
            return 1
    else:
        print("-> 记录已修好，未切换任何界面（电视上正在播的东西不受影响）")

    print("\n完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
