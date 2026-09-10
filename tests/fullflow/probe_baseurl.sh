#!/usr/bin/env bash
# 鸿蒙门2 挂账收口：probe_bad_baseurl 全自动重试（每步 dump 动态解析，固定坐标仅限 ☰/⚙）
export MSYS_NO_PATHCONV=1
HDC="D:/Huawei/DevEcoStudio/sdk/default/openharmony/toolchains/hdc.exe"
BUNDLE="com.zhuolin.yunkai"
EV='D:\MyAIWorkspace\project\yunkai\yunkai-harmony\tests\fullflow\reports\2026-09-10_214023_ui\artifacts\agent-ui\run1'
DUMP='D:\MyAIWorkspace\project\yunkai\yunkai-harmony\tests\fullflow\_dump.json'
mkdir -p "/d/MyAIWorkspace/project/yunkai/yunkai-harmony/tests/fullflow/reports/2026-09-10_214023_ui/artifacts/agent-ui/run1"

dump() { "$HDC" shell uitest dumpLayout -p /data/local/tmp/d.json >/dev/null 2>&1; "$HDC" file recv /data/local/tmp/d.json "$DUMP" >/dev/null 2>&1; }
tap() { "$HDC" shell uitest uiInput click "$1" "$2" >/dev/null 2>&1; sleep 2.5; }
wake() { "$HDC" shell power-shell wakeup >/dev/null 2>&1; "$HDC" shell power-shell setmode 602 >/dev/null 2>&1; sleep 2; }
launch() { "$HDC" shell aa force-stop "$BUNDLE" >/dev/null 2>&1; sleep 1; "$HDC" shell aa start -a EntryAbility -b "$BUNDLE" >/dev/null 2>&1; sleep 9; }

resolve() { # resolve <text|type> <值> → 打印 "x y"，空=未找到
  dump
  DUMP_PATH="$DUMP" MODE="$1" ARG="$2" python -c "
import json, os, re, sys
s = open(os.environ['DUMP_PATH'], encoding='utf-8', errors='replace').read()
try: data = json.loads(s, strict=False)
except Exception:
    i = s.rfind(']'); data = json.loads(s[:i+1], strict=False)
mode = os.environ['MODE']; arg = os.environ['ARG']; hits = []
def w(n):
    if isinstance(n, dict):
        at = n.get('attributes') or {}
        t = at.get('text') or ''; ty = at.get('type') or ''
        if mode == 'text' and arg in t: hits.append(at.get('bounds'))
        if mode == 'type' and ty == arg: hits.append(at.get('bounds'))
        for c in (n.get('children') or []): w(c)
    elif isinstance(n, list):
        for c in n: w(c)
walk_node = w
w(data)
if hits:
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', hits[0])
    if m: print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)
"
}
tap_resolve() { XY=$(resolve "$1" "$2"); if [ -z "$XY" ]; then echo "  FAIL 找不到 $1=$2"; return 1; fi; echo "  $1=$2 → $XY"; read X Y <<< "$XY"; tap "$X" "$Y"; }
saw() { dump >/dev/null 2>&1; DUMP_PATH="$DUMP" ARG="$1" python -c "
import os
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
print(('PASS 见到「'+os.environ['ARG']+'」') if os.environ['ARG'] in s else ('FAIL 未见「'+os.environ['ARG']+'」'))
"; }

# ── 流程 ──
wake; launch
echo "[1] 进设置"; tap 93 204; sleep 1; tap 1128 366; sleep 3
echo "[2] 定位 API 地址 TextInput"
FXY=$(resolve type TextInput | head -1); echo "  field1 → $FXY"
read FX FY <<< "$FXY"
tap "$FX" "$FY"; sleep 1.2
echo "[3] 全选+删除，注入无效域"
"$HDC" shell uitest uiInput keyEvent 2082 >/dev/null 2>&1; sleep 0.6
for i in $(seq 1 45); do "$HDC" shell uitest uiInput keyEvent 2055 >/dev/null 2>&1; done; sleep 1.2
"$HDC" shell "uitest uiInput inputText $FX $FY 'https://invalid.example.invalid/v1'" >/dev/null 2>&1; sleep 2
dump; saw "invalid.example"
echo "[4] 收键盘，滚动找「保存」"
"$HDC" shell uitest uiInput keyEvent Back >/dev/null 2>&1; sleep 1.5
FOUND=""
for i in 1 2 3 4 5 6; do
  XY=$(resolve text "保存")
  if [ -n "$XY" ]; then FOUND="$XY"; break; fi
  "$HDC" shell uitest uiInput swipe 660 1600 660 700 700 >/dev/null 2>&1; sleep 1.5
done
if [ -z "$FOUND" ]; then echo "  FAIL 滚动后仍找不到保存"; else
  echo "  保存 → $FOUND"; read SX SY <<< "$FOUND"; tap "$SX" "$SY"; sleep 2.5
fi
echo "[5] 回对话页"; tap 93 204; sleep 2
echo "[6] 发消息（输入框动态定位）"
IXY=$(resolve type TextInput); echo "  输入框 → $IXY"; read IX IY <<< "$IXY"
"$HDC" shell "uitest uiInput inputText $IX $IY '你好'" >/dev/null 2>&1; sleep 1.5
SXY=$(resolve text "↑"); echo "  ↑ → $SXY"; read UX UY <<< "$SXY"; [ -n "$UX" ] && tap "$UX" "$UY"
sleep 14
shot() { R="/data/local/tmp/e_$(date +%H%M%S).png"; "$HDC" shell uitest screenCap -p "$R" >/dev/null 2>&1; "$HDC" file recv "$R" "$EV\\$1" >/dev/null 2>&1; }
shot 13_error_probe.png
dump; saw "出错了"
echo "[7] 恢复正确地址"; tap 93 204; sleep 1; tap 1128 366; sleep 3
FXY=$(resolve type TextInput); read FX FY <<< "$FXY"; tap "$FX" "$FY"; sleep 1.2
"$HDC" shell uitest uiInput keyEvent 2082 >/dev/null 2>&1; sleep 0.5
for i in $(seq 1 45); do "$HDC" shell uitest uiInput keyEvent 2055 >/dev/null 2>&1; done; sleep 1
"$HDC" shell "uitest uiInput inputText $FX $FY 'https://open.bigmodel.cn/api/paas/v4'" >/dev/null 2>&1; sleep 1.5
"$HDC" shell uitest uiInput keyEvent Back >/dev/null 2>&1; sleep 1.5
FOUND=""
for i in 1 2 3 4 5 6; do XY=$(resolve text "保存"); if [ -n "$XY" ]; then FOUND="$XY"; break; fi; "$HDC" shell uitest uiInput swipe 660 1600 660 700 700 >/dev/null 2>&1; sleep 1.5; done
[ -n "$FOUND" ] && { read SX SY <<< "$FOUND"; tap "$SX" "$SY"; sleep 2; echo "  已恢复并保存"; }
dump >/dev/null 2>&1; saw "open.bigmodel"
