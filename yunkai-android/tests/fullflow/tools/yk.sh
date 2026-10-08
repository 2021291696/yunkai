#!/usr/bin/env bash
# yk.sh — 云开真机速控（2026-10-08）：装机/注入/日志/状态/帧统计一键化
# 用法: ./yk.sh <cmd> [args]   （串口默认 df07a4cd，可 export YK_SERIAL 覆盖）
#   install [debug|release]  构建+装机（默认 release），装完自动切回百度输入法
#   send "文本"              云开发消息（自动切 ADBKeyboard、动态 dump 找发送钮；发完留在 ADB IME）
#   ime [adb|baidu|status]   切输入法 / 查看当前
#   log                      跟踪 yunkai 日志（Ctrl-C 停止）
#   state                    屏幕/锁屏/前台/无障碍/悬浮窗 一屏速览
#   gfx reset|read           帧统计重置 / 读取（p50/p90/janky）
#   wake                     唤醒屏幕
#   db                       拉 yunkai.db 到临时目录（仅 debug 包可用，release run-as 失效）
set -euo pipefail
SERIAL="${YK_SERIAL:-df07a4cd}"
ADB="/d/Android/Sdk/platform-tools/adb.exe"
PROJ="/d/MyAIWorkspace/project/yunkai/yunkai-android"
PKG="com.zhuolin.yunkai"
A() { "$ADB" -s "$SERIAL" "$@"; }

cmd="${1:-help}"; shift || true
case "$cmd" in
  install)
    kind="${1:-release}"
    cap="$(echo "$kind" | sed 's/^./\U&/')"
    cd "$PROJ"
    export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot" ANDROID_HOME="D:\Android\Sdk"
    ./gradlew "assemble$cap" -q
    A install -r "app/build/outputs/apk/$kind/app-$kind.apk"
    A shell ime set com.baidu.input_mi/.ImeService >/dev/null
    echo "已装 $kind 包，输入法已还原百度"
    ;;
  send)
    text="${1:?用法: yk.sh send \"文本\"}"
    A shell ime set com.android.adbkeyboard/.AdbIME >/dev/null
    A shell am start -n "$PKG/.MainActivity" >/dev/null
    sleep 2
    A shell input tap 640 2534          # 输入框（键盘收起位）
    sleep 1.2
    A shell "am broadcast -a ADB_INPUT_TEXT --es msg '$text'" >/dev/null
    sleep 1
    # 动态找发送钮：键盘升起后位置随布局漂移，dump 取右下象限（x>1000,y>2200）最靠下的可点区中心
    A shell uiautomator dump /sdcard/yk_dump.xml >/dev/null 2>&1 || true
    A exec-out cat /sdcard/yk_dump.xml > /tmp/yk_dump.xml 2>/dev/null || true
    center=$(python - <<'PYEOF'
import io, re
try:
    s = io.open(r"C:\Users\20212\AppData\Local\Temp\yk_dump.xml", encoding="utf-8", errors="ignore").read()
except OSError:
    s = ""
best = None
for m in re.finditer(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s):
    x1, y1, x2, y2 = map(int, m.groups())
    if x1 > 1000 and y1 > 2200:
        c = ((x1 + x2) // 2, (y1 + y2) // 2)
        if best is None or c[1] > best[1]:
            best = c
print(f"{best[0]} {best[1]}" if best else "")
PYEOF
)
    [ -z "$center" ] && center="1149 2475"   # dump 失败时的兜底位（键盘升起态实测值）
    A shell input tap $center
    echo "已发送: $text（发送钮 $center；IME 留在 ADBKeyboard，还原请执行 yk.sh ime baidu）"
    ;;
  ime)
    case "${1:-status}" in
      adb)   A shell ime set com.android.adbkeyboard/.AdbIME ;;
      baidu) A shell ime set com.baidu.input_mi/.ImeService ;;
      *)     A shell settings get secure default_input_method ;;
    esac
    ;;
  log)
    A logcat -s yunkai -v time
    ;;
  state)
    echo "屏幕:   $(A shell dumpsys power | grep -m1 -oE 'mWakefulness=[A-Za-z]+')"
    echo "锁屏:   $(if A shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | grep -q systemui; then echo 是; else echo 否; false; true; fi 2>/dev/null)"
    echo "前台:   $(A shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed 's/[}]$//; s/.*u0 //; s/ t[0-9]*$//' | tr -d '
')"
    echo "输入法: $(A shell settings get secure default_input_method)"
    echo "无障碍: enabled=$(A shell settings get secure enabled_accessibility_services) bound=$(A shell dumpsys accessibility | grep -c 'com.zhuolin.yunkai')"
    echo "悬浮窗: $(A shell dumpsys window windows | grep -c 'type=2032') 个 overlay"
    ;;
  gfx)
    case "${1:-read}" in
      reset) A shell dumpsys gfxinfo "$PKG" reset >/dev/null && echo "帧统计已重置" ;;
      *)     A shell dumpsys gfxinfo "$PKG" | grep -E "Total frames|Janky frames|percentile|Slow" | head -8 ;;
    esac
    ;;
  wake)
    A shell input keyevent KEYCODE_WAKEUP
    ;;
  db)
    out="${TMP:-/tmp}/ykdb_$(date +%H%M%S)"
    mkdir -p "$out"
    for f in yunkai.db yunkai.db-wal; do A exec-out "run-as $PKG cat databases/$f" > "$out/$f" 2>/dev/null; done
    echo "已拉到 $out（sqlite 打开前需 wal 合并或连 w_read）"
    ;;
  *)
    grep -E "^#   " "$0" | sed 's/^#   //'
    ;;
esac
