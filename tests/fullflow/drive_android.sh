#!/usr/bin/env bash
# yunkai-android 门2（界面层）驱动脚本 —— AI 驱动通道用
#
# 用法：bash drive_android.sh <证据目录> <步骤...>
#   证据目录 = tests/fullflow/reports/<时间戳>_ui/artifacts/agent-ui/<runID>（Windows 风格亦可，内部转换）
#   步骤名见文件末尾 usage 段；不传步骤 = 打印可用步骤
#
# 平台假设（本机 AVD quizlens_test）：串口 emulator-5556、屏 320x640 级（聊天输入框 135,537 / 发送 280,537）
#   —— 换了 AVD 分辨率要先改这两个固定坐标（其余坐标全部由 dump 动态解析）
#
# 平台坑（本轮实测，改动本脚本前先读）：
#  0) ADBKeyboard.apk 随仓在 tests/fullflow/tools/（`adb install -r` + `ime set com.android.adbkeyboard/.AdbIME`），不用重新下载
#  1) AVD 无中文 IME：`input text` 与 MCP android_ui_type_text 对非 ASCII 都报 `Attempt to get length of null array`
#     → 装 ADBKeyboard（`adb install -r ADBKeyboard.apk` + `ime set com.android.adbkeyboard/.AdbIME`）+ broadcast 注入
#  2) `adb shell` 会把参数里的空格当分隔符**再切一次词**（设备侧 shell 二次解析）→ 含空格的文本必须给设备侧加引号
#  3) 多行文本 = 逐行 broadcast + `input keyevent 66`(ENTER)
#  4) `uiautomator dump` **会漏长文本节点**（长回答整段不出现）、Toast 也不在树里 → 内容级断言看截图，机制证据看 logcat
#  5) 屏幕操作前先 keyevent 224 唤醒；打字后 IME 遮挡底部按钮时先 keyevent 4 收键盘（脚本内 hide_ime 已判 mInputShown）
export MSYS_NO_PATHCONV=1
ADB="/d/Android/Sdk/platform-tools/adb.exe -s emulator-5556"
PKG="com.zhuolin.yunkai"

EV_WIN="${1:?用法: bash drive_android.sh <证据目录> <步骤...>}"
shift
EV="$(printf '%s' "$EV_WIN" | tr '/' '\\')"
EV_LOCAL="$(printf '%s' "$EV_WIN" | sed 's|^\([A-Za-z]\):|/\L\1|' | tr '\\' '/')"
DUMP_WIN="$(printf '%s' "$EV_WIN" | tr '/' '\\')\\_dump.json"   # 设备侧 dump 落盘位（Windows 反斜杠：adb/hdc file recv 才认）
DUMP="$DUMP_WIN"
mkdir -p "$EV_LOCAL"

tap() { $ADB shell input tap "$1" "$2" >/dev/null 2>&1; sleep 2; }
wake() { $ADB shell input keyevent 224 >/dev/null 2>&1; sleep 0.5; }
launch() { $ADB shell am force-stop "$PKG" >/dev/null 2>&1; sleep 1; $ADB shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 6; }
dump() { $ADB shell uiautomator dump /sdcard/d.xml >/dev/null 2>&1; $ADB pull /sdcard/d.xml "$DUMP_WIN" >/dev/null 2>&1; }
texts() { DUMP_PATH="$DUMP_WIN" python -c "
import re,os
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
print('   ' + ' | '.join([t for t in re.findall(r'text=\"([^\"]*)\"',s) if t.strip()][:18]))
"; }
expect() { DUMP_PATH="$DUMP_WIN" NEEDLE="$1" python -c "
import os,re
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
ts=[t for t in re.findall(r'text=\"([^\"]*)\"',s) if t.strip()]
n=os.environ['NEEDLE']
print(('  PASS 见到「'+n+'」') if any(n in t for t in ts) else ('  FAIL 未见「'+n+'」'))
"; }
absent() { DUMP_PATH="$DUMP_WIN" NEEDLE="$1" python -c "
import os
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
n=os.environ['NEEDLE']
print(('  PASS 确认无「'+n+'」') if n not in s else ('  FAIL 仍出现「'+n+'」'))
"; }
swipe_up() { $ADB shell input swipe 160 520 160 160 250 >/dev/null 2>&1; sleep 1.2; }
ime_shown() { $ADB shell dumpsys input_method 2>/dev/null | python -c "
import sys
print('1' if 'mInputShown=true' in sys.stdin.read() else '0')
"; }
hide_ime() { if [ "$(ime_shown)" = "1" ]; then $ADB shell input keyevent 4 >/dev/null 2>&1; sleep 1; fi; }
shot() { R="/sdcard/shot_$(date +%H%M%S)_$RANDOM.png"; $ADB shell screencap -p "$R" >/dev/null 2>&1; $ADB pull "$R" "$EV\\$1" >/dev/null 2>&1
  F="$EV_LOCAL/$1"; if [ -f "$F" ] && head -c 4 "$F" | od -An -tx1 | tr -d ' \n' | grep -qi "89504e47"; then echo "  [证据] $1 OK"; else echo "  [证据] $1 FAIL"; fi; }
xy_of() { dump; DUMP_PATH="$DUMP_WIN" TARGET="$1" python -c "
import re,os
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
t=os.environ['TARGET']
for m in re.finditer(r'<node[^>]*>', s):
    seg=m.group(0)
    tm=re.search(r'text=\"([^\"]*)\"',seg)
    if not tm or t not in tm.group(1): continue
    b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',seg)
    if b:
        print((int(b.group(1))+int(b.group(3)))//2, (int(b.group(2))+int(b.group(4)))//2); break
"; }
tap_text() { XY=$(xy_of "$1"); if [ -z "$XY" ]; then echo "  tap_text FAIL 找不到「$1」"; return 1; fi; echo "  tap_text「$1」→ $XY"; tap $XY; }
tap_text_scroll() { for i in 1 2 3 4 5; do XY=$(xy_of "$1"); if [ -n "$XY" ]; then echo "  tap_text_scroll「$1」→ $XY"; tap $XY; return 0; fi; swipe_up; done; echo "  FAIL 找不到「$1」"; return 1; }
field() { dump; XY=$(DUMP_PATH="$DUMP_WIN" N="$1" python -c "
import re,os
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
n=int(os.environ['N']); i=0
for m in re.finditer(r'<node[^>]*>', s):
    seg=m.group(0)
    if 'EditText' not in seg: continue
    i+=1
    if i==n:
        b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',seg)
        print((int(b.group(1))+int(b.group(3)))//2, (int(b.group(2))+int(b.group(4)))//2); break
"); if [ -z "$XY" ]; then echo "  field $1 FAIL"; return 1; fi; echo "  第 $1 个输入框 → $XY"; tap $XY; }
type_cn() { $ADB shell "am broadcast -a ADB_INPUT_TEXT --es msg '$1'" >/dev/null 2>&1; sleep 1.5; }
type_lines() { for line in "$@"; do $ADB shell "am broadcast -a ADB_INPUT_TEXT --es msg '$line'" >/dev/null 2>&1; sleep 0.9; $ADB shell input keyevent 66 >/dev/null 2>&1; sleep 0.7; done; }
clear_field() { tap "$1" "$2"; sleep 1; $ADB shell input keycombination 113 29 >/dev/null 2>&1; sleep 0.4; $ADB shell input keyevent 67 >/dev/null 2>&1; sleep 0.6; }
type_chat() { tap 135 537; sleep 1; type_cn "$1"; }
send_chat() { hide_ime; tap 280 537; }
to_settings() { tap_text '☰'; sleep 1; tap_text '⚙'; sleep 2; }

s1_launch_guide() { wake; launch; dump; echo "① 引导页："; texts; shot 01_launch_guide.png; expect '问我任何问题'; }
s2_guard() { wake; launch; type_cn '你好'; send_chat; sleep 1; $ADB shell screencap -p /sdcard/s.png >/dev/null 2>&1; $ADB pull /sdcard/s.png "$EV\\02_guard_toast.png" >/dev/null 2>&1; shot 02b_after.png; }
s3_config() { wake; launch; to_settings; field 1; type_cn 'https://api.deepseek.com/v1'; field 2; type_cn "$DEEPSEEK_API_KEY"; field 3; type_cn 'deepseek-chat'
  hide_ime; tap_text_scroll '保存'; sleep 2; launch; to_settings; dump; expect 'api.deepseek.com'; shot 03_restored.png; }
s4_skill_create() { wake; launch; to_settings; tap_text_scroll '技能库'; sleep 2; tap_text '＋ 新建'; sleep 2; field 1; type_cn '测试技能-ab12'
  field 2; type_cn 'run-all 门2 自建技能'; field 3; type_cn '全流程测试技能正文：请回答「测试通过」四个字。'; hide_ime; tap_text_scroll '保存'; sleep 2; dump; expect '测试技能-ab12'; }
s4b_skill_import() { wake; launch; to_settings; tap_text_scroll '技能库'; sleep 2; tap_text '粘贴导入'; sleep 2; field 1; clear_field 160 368
  type_lines '---' 'name: 全流程导入技能' 'description: run-all 门2 粘贴导入' '---' '正文：请回答「导入成功」。'
  hide_ime; tap_text_scroll '解析并导入'; sleep 2; dump; expect '全流程导入技能'; }
s5_bare_llm() { wake; launch; type_chat '用一句话回答：1+1等于几？'; send_chat; sleep 30; dump; echo "⑤ 回答："; texts; shot 05_bare_llm.png; }
s6_search() { wake; launch; type_chat '今天有什么科技新闻？'; send_chat; sleep 12; dump; echo "⑥ 加载中："; texts
  sleep 40; dump; echo "⑥ 回答："; texts; shot 06_answer.png; $ADB logcat -d -s yunkai 2>/dev/null | tail -8; }
s7_cancel() { wake; launch; type_chat '详细介绍一下人工智能的历史'; send_chat; sleep 6; dump; echo "⑦ 加载中（应见 取消）："; texts
  tap_text '取消'; sleep 3; dump; echo "⑦ 取消后："; texts; shot 07_cancelled.png; }
s8_eli5() { wake; launch; tap_text '@eli5 讲讲黑洞是怎么形成的'; sleep 150; dump; expect '画布'; tap_text '画布 · 点此全屏查看'; sleep 4; dump; echo "⑧ 全屏画布："; texts; shot 08_canvas.png; }
s9_history() { wake; launch; tap_text '☰'; sleep 1; dump; echo "⑨ 抽屉："; texts; tap_text '＋ 新对话'; sleep 2; dump; expect '问我任何问题'; shot 09_new_conv.png; }
s10_dismiss() { wake; launch; tap_text '☰'; sleep 1; tap_text '✕'; sleep 1; tap_text '☰'; sleep 1; tap 290 300; sleep 2
  type_chat '抽屉已收起'; dump; expect '抽屉已收起'; shot 10_dismiss.png; }
s15_switch_loading() { wake; launch; type_chat '今天有什么科技新闻？'; send_chat; sleep 4; tap_text '☰'; sleep 1
  tap_text '详细介绍一下人工智能的历史'; sleep 5; dump; echo "⑮ 切换后："; texts
  $ADB shell dumpsys activity activities 2>/dev/null | python -c "
import sys
for l in sys.stdin:
    if 'topResumedActivity' in l: print(' 前台:', l.strip()[:110]); break
"
  sleep 40; dump; echo "⑮ 等 40s（旧轮跑完）后："; texts; shot 15_stale_check.png; absent '科技新闻'; }

usage() { echo "可用步骤：1 2 3 4 4b 5 6 7 8 9 10 15（对应 manifest 的 ui 步骤）"; }
[ $# -eq 0 ] && { usage; exit 0; }
for st in "$@"; do case "$st" in
  1) s1_launch_guide;; 2) s2_guard;; 3) s3_config;; 4) s4_skill_create;; 4b) s4b_skill_import;;
  5) s5_bare_llm;; 6) s6_search;; 7) s7_cancel;; 8) s8_eli5;; 9) s9_history;; 10) s10_dismiss;; 15) s15_switch_loading;;
  *) echo "未知步骤 $st"; usage;; esac; done
