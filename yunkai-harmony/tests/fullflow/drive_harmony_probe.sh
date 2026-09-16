#!/usr/bin/env bash
# 挂账收口·终版B：node_xy.py/field_values.py 做定位器；字段清空=MOVE_END+DEL×50；滚动不收键盘
export MSYS_NO_PATHCONV=1
HDC="D:/Huawei/DevEcoStudio/sdk/default/openharmony/toolchains/hdc.exe"
BUNDLE="com.zhuolin.yunkai"
TESTS="D:/MyAIWorkspace/project/yunkai/yunkai-harmony/tests/fullflow"
EV='D:\MyAIWorkspace\project\yunkai\yunkai-harmony\tests\fullflow\reports\2026-09-10_214023_ui\artifacts\agent-ui\run1'
DUMP="$TESTS\\_dump.json"
DUMP_LOCAL="$TESTS/_dump.json"

tap() { "$HDC" shell uitest uiInput click "$1" "$2" >/dev/null 2>&1; sleep 2.5; }
wake() { "$HDC" shell power-shell wakeup >/dev/null 2>&1; "$HDC" shell power-shell setmode 602 >/dev/null 2>&1; sleep 2; }
launch() { "$HDC" shell aa force-stop "$BUNDLE" >/dev/null 2>&1; sleep 1; "$HDC" shell aa start -a EntryAbility -b "$BUNDLE" >/dev/null 2>&1; sleep 9; }
dump() { "$HDC" shell uitest dumpLayout -p /data/local/tmp/d.json >/dev/null 2>&1; "$HDC" file recv /data/local/tmp/d.json "$DUMP" >/dev/null 2>&1; }
xy() { (cd "$TESTS" && python node_xy.py "$DUMP_LOCAL" "$1" "${2:-}" 2>/dev/null); }
saw() { dump; (cd "$TESTS" && python -c "
import sys
from pathlib import Path
s = Path('_dump.json').read_text(encoding='utf-8', errors='replace')
n = sys.argv[1]
print(('PASS 见到「' + n + '」') if n in s else ('FAIL 未见「' + n + '」'))
" "$1"); }
swipe_up() { "$HDC" shell uitest uiInput swipe 660 1100 660 300 600 >/dev/null 2>&1; sleep 1.4; }

wake; launch
echo "[1] ☰ → 抽屉"; tap 93 204; saw "＋ 新对话"
echo "[2] ⚙ → 设置"; tap 1128 366; saw "外观"
echo "[3] 点第1个 TextInput（API 地址）"; tap 660 1043; sleep 1.2
echo "[4] MOVE_END + DEL×50 清空"; "$HDC" shell uitest uiInput keyEvent 2082 >/dev/null 2>&1; sleep 0.5
for i in $(seq 1 50); do "$HDC" shell uitest uiInput keyEvent 2055 >/dev/null 2>&1; done; sleep 1
dump; VAL=$(cd "$TESTS" && python field_values.py "$DUMP_LOCAL" 2>/dev/null | head -1); echo "  清空后字段1: [$VAL]"
echo "[5] 注入无效域"; "$HDC" shell "uitest uiInput inputText 660 1043 'https://invalid.example.invalid/v1'" >/dev/null 2>&1; sleep 1.8
dump; saw "invalid.example"
echo "[6] 滚动（不收键盘，IME 在下半屏，滚动在上半屏）找「保存」"
FOUND=""
for i in $(seq 1 14); do
  XY=$(cd "$TESTS" && python node_xy.py "$DUMP_LOCAL" "保存" 2>/dev/null)
  if [ -n "$XY" ]; then FOUND="$XY"; echo "  第 $i 轮找到保存 → $XY"; break; fi
  swipe_up
done
if [ -z "$FOUND" ]; then echo "结论：仍找不到保存，保持挂账"; exit 1; fi
read SX SY <<< "$FOUND"; tap "$SX" "$SY"; sleep 2.5
echo "[7] ‹(93,190) 回对话页"; tap 93 190; sleep 3
echo "[8] 点 TextInput 注入「你好」"
IXY=$(cd "$TESTS" && python node_xy.py "$DUMP_LOCAL" "" TextInput 2>/dev/null | head -1)
echo "  输入框 → $IXY"; read IX IY <<< "$IXY"
tap "$IX" "$IY"; sleep 1.2
"$HDC" shell "uitest uiInput inputText $IX $IY '你好'" >/dev/null 2>&1; sleep 1.5
echo "[9] 定位 ↑ 并点击（动态）"
UXY=$(cd "$TESTS" && python node_xy.py "$DUMP_LOCAL" "↑" Button 2>/dev/null)
echo "  ↑ → $UXY"; read UX UY <<< "$UXY"
tap "$UX" "$UY"
sleep 2; R="/data/local/tmp/e1.png"; "$HDC" shell uitest screenCap -p "$R" >/dev/null 2>&1; "$HDC" file recv "$R" "$EV\\13a_error_toast.png" >/dev/null 2>&1; echo "  [证据] 13a_error_toast.png"
sleep 3; R="/data/local/tmp/e2.png"; "$HDC" shell uitest screenCap -p "$R" >/dev/null 2>&1; "$HDC" file recv "$R" "$EV\\13b_error_after.png" >/dev/null 2>&1; echo "  [证据] 13b_error_after.png"
"$HDC" shell hilog -x 2>/dev/null | grep -iE "send failed|invalid.example" | tail -4 > "$TESTS/_hilog_send.txt"
echo "  hilog 取证:"; cat "$TESTS/_hilog_send.txt"
echo "[10] 恢复正确地址"
tap 93 204; sleep 1; tap 1128 366; sleep 3
tap 660 1043; sleep 1.2
"$HDC" shell uitest uiInput keyEvent 2082 >/dev/null 2>&1; sleep 0.5
for i in $(seq 1 50); do "$HDC" shell uitest uiInput keyEvent 2055 >/dev/null 2>&1; done; sleep 1
"$HDC" shell "uitest uiInput inputText 660 1043 'https://open.bigmodel.cn/api/paas/v4'" >/dev/null 2>&1; sleep 1.8
FOUND=""
for i in $(seq 1 14); do
  XY=$(cd "$TESTS" && python node_xy.py "$DUMP_LOCAL" "保存" 2>/dev/null)
  if [ -n "$XY" ]; then FOUND="$XY"; break; fi
  swipe_up
done
if [ -n "$FOUND" ]; then read SX SY <<< "$FOUND"; tap "$SX" "$SY"; sleep 2; echo "  已恢复保存"; fi
dump; saw "open.bigmodel"
