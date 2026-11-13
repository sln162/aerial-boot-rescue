[中文](README.md) | [English](README.en.md)

# Aerial Home Direct

On XGIMI projectors (the Android 9 era firmware), when you use Aerial as your home screen, pressing the Home key pops up the system's "which home screen?" chooser first. It takes a few seconds to come back, and sometimes it just hangs.

This accessibility service dismisses that chooser and brings Aerial to the front, **0.4 s from key press to the home screen**. It also covers the black screen right after boot.

It does not write the home preference, does not send a Back key, does not disable XGIMI's own launcher, and does not require setting up dual home.

![UI](docs/ui.png)

## What it solves

- **Pressing Home inside another app hangs on the chooser for a few seconds**: the service dismisses it the moment it appears, then pulls Aerial up directly.
- **Dual-home rules randomly disappear**: nothing depends on those records — zero preferred-home entries still works.
- **All-black screen after boot, or landing back on the XGIMI launcher after a shutdown**: after boot it needs two consecutive rounds with no usable foreground before it acts; after a shutdown (deep sleep) the wake-up trigger uses the system's own sleep accounting, so even a ten-second sleep counts (see "When it decides to rescue").

## Compatibility

The criterion is not the model — it is **whether the system is showing a resolver chooser** (`com.xgimi.xgimiframeworkui...resolver.ResolverActivity` on this XGIMI firmware, `android/com.android.internal.app.ResolverActivity` on AOSP). As long as it goes through that, the model does not matter.

- **Android 9 XGIMI: measured working** (on an H3S: 180+ Home presses, several reboots, several shutdown/wake cycles)
- **Android 11 and newer XGIMI: not measured.** Same mechanism (resolver + accessibility node taps) and expected to work; if it cannot recognize the chooser it does nothing rather than tapping some other window.
- **Other brands / AOSP**: the same path applies whenever the system resolver is shown. The criterion is the class name plus the app label the system provides; no brand-specific Chinese string is hard-coded. Not measured.

## How fast

20 presses in a row, 20/20:

```
+0~1ms      click row Aerial=true
+106~126ms  click 仅此一次 (round 1)
+13ms       pull: start aerial right after click 仅此一次
+399~448ms  check#1 front=app.orchard.tv
```

It taps the Aerial row the moment the chooser appears (that is when the button finally becomes enabled), taps "Just once" as soon as it lights up, and pulls Aerial itself at that same instant — beating the chooser's default candidate (the XGIMI launcher), so you never see it flash.

Accessibility events are occasionally missed; a 1-second watchdog covers that. In practice 2 out of 34 presses went that way, with the same sub-0.5 s arrival.

## Impact on the device

It does not scan node trees on a timer. The main path is accessibility events — it acts the moment the chooser appears, with zero delay. There is also a once-a-second watchdog, and each round only reads "which package owns the current window" (no tree traversal, no subprocess, no reading or writing system settings). It only actually taps when the foreground is the XGIMI framework or the screen is entirely black.

Measured (XGIMI H3S, 3.5 GB total RAM): `top` and `dumpsys cpuinfo` usually show `0.0%`; 3 seconds of CPU total over the first 7 minutes after install; PSS about 16 MB, 0.4%.

Compared with what it guards, both measured on the same screen:

| | This guard | Aerial itself |
|---|---|---|
| CPU (screen on, idle) | 0.45% of one core | 10.3% of one core |
| Memory PSS | 16 MB | 166 MB |

So it uses about 1/23 of Aerial's CPU and 1/10 of its memory.

The cost, stated plainly: the once-a-second watchdog is about 4.5 ms of CPU and interrupts CPU idle (negligible for a projector on mains power); while the device is truly asleep the Handler is frozen and nothing is spent, so standby power is unaffected.

## How it recognizes things

It finds the "Aerial" row by the label the system provides, so it works in any language; for "Just once" it first checks the view ID, then several languages' text, and if it recognizes nothing it does not guess — it taps the only button that is enabled before a candidate is chosen (Cancel), which dismisses the chooser anyway, and then pulls Aerial itself. It never gambles on "which one is Just once" by position or order: getting that wrong taps "Always" and writes the preference onto the vendor launcher.

## When it decides to rescue

Two entry points, one gate.

**Boot**: after the boot animation (`init.svc.bootanim`) stops, it needs two consecutive rounds with no usable foreground before pulling Aerial once. If any other window is visible, it leaves things alone.

**Wake**: the length of the sleep does not matter. The system keeps two clocks — `elapsedRealtime` includes deep sleep, `uptimeMillis` does not — so if the difference between two watchdog rounds grows, the device really slept, even for a dozen seconds. A second backstop treats a watchdog gap over 20 seconds as a wake too, which catches the case where the process was frozen by the system without the device actually sleeping.

After waking, either the XGIMI launcher in front or nothing at all (a black screen) counts as "should rescue"; an empty foreground needs two consecutive rounds, so a single flicker does not trigger it. Any other app is never taken over.

Both paths, plus "pull Aerial right after dismissing the chooser", share one gate: at least 5 seconds between rescues, and at most 3 rescues per episode. The budget is per episode, not per process — a process can live for days, and a per-process budget would never reset.

When the system rebinds the service, only the newest instance acts: the old one has lost its accessibility connection and always reads an empty foreground, so leaving it alive means it misreads a black screen and pulls Aerial again while Aerial is already up.

## A problem met during development

During those seconds when pressing Home seems stuck, there is no visible dialog and it looks like a hang — but the "which home screen?" chooser has already appeared; it is just not drawn yet.

That one took a long time to work out, and it is finally solved.

## Installation

1. Download the APK from [Releases](https://github.com/sln162/aerial-home/releases). If an in-place install fails, uninstall the old one first (debug signature).
2. Three adb commands:

```
pm grant com.sln162.aerialhome android.permission.WRITE_SECURE_SETTINGS
settings put secure enabled_accessibility_services \
  'com.xgimi.duertts/.MonitorService:app.orchard.tv/.guard.HomeGuardService:com.sln162.aerialhome/com.sln162.aerialhome.BootA11yService'
settings put secure accessibility_enabled 1
```

3. Open the app once; "Running state" should show a green "OK".

`pm grant` is not optional: XGIMI rewrites the accessibility list at the moment it unlocks and drops third-party services. Without that permission the service cannot write itself back.

## Updating

Install over the existing copy. No uninstall, no re-granting.

```
adb install -r AerialHome-<version>.apk
```

Then open the app and check "Running state": green "OK" means done. If not, toggle the accessibility switch off and on — XGIMI rewrites the accessibility list when it unlocks; the service normally writes itself back, and only if that fails do you need to turn it on by hand.

A `bound, uptime=` line in the log means the system has bound the service.

## What the UI shows

| Section | Content |
|---|---|
| Running state | OK / not running / permissions incomplete |
| Last black-screen rescue | Time of the most recent rescue, or "not yet", plus the running total |
| Home rules | What the boot query and the Home-key query each resolve to; if both are ResolverActivity it says "no dual home set" |
| Home presses | Total count; the last 8 with time, `+Nms`, and how it was handled |
| Recent log | Last 10 log lines, with long package names shortened to "XGIMI chooser" / "three-entry accessibility list" |

No window pops up at boot; open the app afterwards to see how many times it acted.

## What it does not do

- **It does not write the HOME preference and does not tap "Always".** In this chooser, "Always" writes the default-selected row (the XGIMI launcher); tapping a candidate row cannot change `selected`, and `ACTION_SELECT` can but leaves the buttons locked.
- **It does not send Back.** Back always goes to the focused window, so pressing Home inside a third-party app would deliver it to that app — measured to hang for seconds.
- **It does not freeze or disable the built-in XGIMI launcher** and does not touch system components.
- **If it cannot recognize the button it does not guess.** It would rather tap Cancel to close the chooser and pull Aerial itself than gamble on position or order.

## Limitations

- After "Just once" is chosen, the chooser starts its default row (the XGIMI launcher); we immediately pull Aerial over it. That step relies on the accessibility service's background-activity-start exemption.
- Occasionally arrival takes 0.6 s; in the first stretch after boot, while the system is busy rewriting the accessibility list, it can go past 1 s.
- Once Aerial fully covers the chooser, its nodes disappear from `getWindows()`, so the action runs inside the event callback with zero delay.
- Only run on Android 9 XGIMI firmware; newer versions are not measured (see "Compatibility").

## Logs

```
adb shell run-as com.sln162.aerialhome cat /data/user_de/0/com.sln162.aerialhome/files/rescue.log
```
