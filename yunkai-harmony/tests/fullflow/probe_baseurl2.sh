#!/usr/bin/env bash
# 挂账收口·第二轮：保存后立即抓 toast（错误响应是秒回的，截图要快）
export MSYS_NO_PATHCONV=1
HDC="D:/Huawei/DevEcoStudio/sdk/default/openharmony/toolchains/hdc.exe"
BUNDLE="com.zhuolin.yunkai"
EV='D:\MyAIWorkspace\project\yunkai\yunkai-harmony\tests\fullflow\reports\2026-09-10_214023_ui\artifacts\agent-ui\run1'
DUMP='D:\MyAIWorkspace\project\yunkai\yunkai-harmony\tests\fullflow\_dump.json'
tap() { "$HDC" shell uitest uiInput click "$1" "$2" >/dev/null 2>&1; sleep 2.5; }
wake() { "$HDC" shell power-shell wakeup >/dev/null 2>&1; "$HDC" shell power-shell setmode 602 >/dev/null 2>&1; sleep 2; }
launch() { "$HDC" shell aa force-stop "$BUNDLE" >/dev/null 2>&1; sleep 1; "$HDC" shell aa start -a EntryAbility -b "$BUNDLE" >/dev/null 2>&1; sleep 9; }
dump() { "$HDC" shell uitest dumpLayout -p /data/local/tmp/d.json >/dev/null 2>&1; "$HDC" file recv /data/local/tmp/d.json "$DUMP" >/dev/null 2>&1; }
saw() { dump; DUMP_PATH="$DUMP" ARG="$1" python -c "
import os
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
n=os.environ['ARG']
print(('PASS 见到「'+n+'」') if n in s else ('FAIL 未见「'+n+'」'))
"; }
resolve() { dump; DUMP_PATH="$DUMP" MODE="$1" ARG="$2" python -c "
import json, os, re
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
w(data)
if hits:
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', hits[0])
    if m: print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)
"; }

wake; launch
echo "[1] 进设置"; tap 93 204; sleep 1; tap 1128 366; sleep 3
FXY=$(resolve type TextInput); echo "[2] field1 → $FXY"; read FX FY <<< "$FXY"
tap "$FX" "$FY"; sleep 1.2
"$HDC" shell uitest uiInput keyEvent 2082 >/dev/null 2>&1; sleep 0.5
for i in $(seq 1 45); do "$HDC" shell uitest uiInput keyEvent 2055 >/dev/null 2>&1; done; sleep 1
"$HDC" shell "uitest uiInput inputText $FX $FY 'https://invalid.example.invalid/v1'" >/dev/null 2>&1; sleep 1.5
dump; saw "invalid.example"
"$HDC" shell uitest uiInput keyEvent Back >/dev/null 2>&1; sleep 1.5
FOUND=""
for i in 1 2 3 4 5 6 7 8; do XY=$(resolve text "保存"); if [ -n "$XY" ]; then FOUND="$XY"; break; fi
  "$HDC" shell uitest uiInput swipe 660 1600 660 750 600 >/dev/null 2>&1; sleep 1.4; done
echo "[3] 保存 → ${FOUND:-未找到}"
if [ -n "$FOUND" ]; then read SX SY <<< "$FOUND"; tap "$SX" "$SY"; sleep 2.5; fi
dump; saw "invalid.example"   # 确认保存生效（字段仍是无效域）
echo "[4] 回对话页发消息"; tap 93 204; sleep 2
IXY=$(resolve type TextInput); read IX IY <<< "$IXY"; echo "  输入框 → $IXY"
"$HDC" shell "uitest uiInput inputText $IX $IY '你好'" >/dev/null 2>&1; sleep 1.5
SXY=$(resolve text "↑"); echo "  ↑ → $SXY"; read UX UY <<< "$SXY"
if [ -n "$UX" ]; then
  tap "$UX" "$UY"
  sleep 2; "$HDC" shell uitest screenCap -p /data/local/tmp/e1.png >/dev/null 2>&1; "$HDC" file recv /data/local/tmp/e1.png "$EV\\13a_toast_fast.png" >/dev/null 2>&1
  sleep 6; "$HDC" shell uitest screenCap -p /data/local/tmp/e2.png >/dev/null 2>&1; "$HDC" file recv /data/local/tmp/e2.png "$EV\\13b_after.png" >/dev/null 2>&1
  dump; saw "出错了"
fi
echo "[5] 恢复地址"; tap 93 204; sleep 1; tap 1128 366; sleep 3
FXY=$(resolve type TextInput); read FX FY <<< "$FXY"; tap "$FX" "$FY"; sleep 1.2
"$HDC" shell uitest uiInput keyEvent 2082 >/dev/null 2>&1; sleep 0.5
for i in $(seq 1 45); do "$HDC" shell uitest uiInput keyEvent 2055 >/dev/null 2>&1; done; sleep 1
"$HDC" shell "uitest uiInput inputText $FX $FY 'https://open.bigmodel.cn/api/paas/v4'" >/dev/null 2>&1; sleep 1.5
"$HDC" shell uitest uiInput keyEvent Back >/dev/null 2>&1; sleep 1.5
FOUND=""
for i in 1 2 3 4 5 6 7 8; do XY=$(resolve text "保存"); if [ -n "$XY" ]; then FOUND="$XY"; break; fi
  "$HDC" shell uitest uiInput swipe 660 1600 660 750 600 >/dev/null 2>&1; sleep 1.4; done
[ -n "$FOUND" ] && { read SX SY <<< "$FOUND"; tap "$SX" "$SY"; sleep 2; echo "[6] 已恢复保存"; }
dump; saw "open.bigmodel"
