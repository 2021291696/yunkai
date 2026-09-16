#!/usr/bin/env bash
# 挂账收口·第三轮：每步 dump 验证页面状态，杜绝状态漂移；错误证据 = 截图 + hilog 双通道
export MSYS_NO_PATHCONV=1
HDC="D:/Huawei/DevEcoStudio/sdk/default/openharmony/toolchains/hdc.exe"
BUNDLE="com.zhuolin.yunkai"
EV='D:\MyAIWorkspace\project\yunkai\yunkai-harmony\tests\fullflow\reports\2026-09-10_214023_ui\artifacts\agent-ui\run1'
DUMP='D:\MyAIWorkspace\project\yunkai\yunkai-harmony\tests\fullflow\_dump.json'
tap() { "$HDC" shell uitest uiInput click "$1" "$2" >/dev/null 2>&1; sleep 2.5; }
key() { "$HDC" shell uitest uiInput keyEvent "$1" >/dev/null 2>&1; sleep 2; }
wake() { "$HDC" shell power-shell wakeup >/dev/null 2>&1; "$HDC" shell power-shell setmode 602 >/dev/null 2>&1; sleep 2; }
launch() { "$HDC" shell aa force-stop "$BUNDLE" >/dev/null 2>&1; sleep 1; "$HDC" shell aa start -a EntryAbility -b "$BUNDLE" >/dev/null 2>&1; sleep 9; }
dump() { "$HDC" shell uitest dumpLayout -p /data/local/tmp/d.json >/dev/null 2>&1; "$HDC" file recv /data/local/tmp/d.json "$DUMP" >/dev/null 2>&1; }
has() { dump; DUMP_PATH="$DUMP" ARG="$1" python -c "
import os
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
n=os.environ['ARG']
print(('PASS 见到「'+n+'」') if n in s else ('FAIL 未见「'+n+'」'))
"; }
type_in() { # 定位第 $1 个 TextInput → 点击 → 清空 → 注入 $2
  dump; DUMP_PATH="$DUMP" N="$1" VAL="$2" python -c "
import json,os,re,subprocess
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
try: d=json.loads(s,strict=False)
except Exception:
    i=s.rfind(']'); d=json.loads(s[:i+1],strict=False)
out=[]
def w(n):
    if isinstance(n,dict):
        at=n.get('attributes') or {}
        if at.get('type')=='TextInput':
            b=at.get('bounds') or ''
            m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
            if m: out.append(((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2))
        for c in (n.get('children') or []): w(c)
    elif isinstance(n,list):
        for c in n: w(c)
w(d)
n=int(os.environ['N'])
if len(out)>=n:
    x,y=out[n-1]; print(x,y)
" ; }
shot() { R="/data/local/tmp/e_$(date +%H%M%S)_$RANDOM.png"; "$HDC" shell uitest screenCap -p "$R" >/dev/null 2>&1; "$HDC" file recv "$R" "$EV\\$1" >/dev/null 2>&1; echo "  [证据] $1"; }

wake; launch
echo "[1] 已在对话页"; has "想聊点什么？"
echo "[2] ☰ → 抽屉"; tap 93 204; has "＋ 新对话"
echo "[3] ⚙ → 设置"; tap 1128 366; has "外观"
echo "[4] 定位第1个 TextInput 并清空注入无效域"
XY=$(dump; DUMP_PATH="$DUMP" python -c "
import json,os,re
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
try: d=json.loads(s,strict=False)
except Exception:
    i=s.rfind(']'); d=json.loads(s[:i+1],strict=False)
out=[]
def w(n):
    if isinstance(n,dict):
        at=n.get('attributes') or {}
        if at.get('type')=='TextInput':
            b=at.get('bounds') or ''
            m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
            if m: out.append(((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2))
        for c in (n.get('children') or []): w(c)
    elif isinstance(n,list):
        for c in n: w(c)
w(d)
print(out[0][0], out[0][1])
")
echo "  field1 → $XY"; read FX FY <<< "$XY"
tap "$FX" "$FY"; sleep 1.2
"$HDC" shell uitest uiInput keyEvent 2082 >/dev/null 2>&1; sleep 0.5
for i in $(seq 1 45); do "$HDC" shell uitest uiInput keyEvent 2055 >/dev/null 2>&1; done; sleep 1
"$HDC" shell "uitest uiInput inputText $FX $FY 'https://invalid.example.invalid/v1'" >/dev/null 2>&1; sleep 1.5
dump >/dev/null 2>&1
echo "[5] 字段注入校验："; has "invalid.example"
echo "[6] 滚动找「保存」并点击"
FOUND=""
for i in 1 2 3 4 5 6 7 8; do
  dump
  if grep -q '"text":"保存"' "$DUMP"; then
    XY=$(DUMP_PATH="$DUMP" python -c "
import re
s=open(r'D:\MyAIWorkspace\project\yunkai\yunkai-harmony\tests\fullflow\_dump.json',encoding='utf-8',errors='replace').read()
m=re.search(r'\"text\":\"保存\"[\s\S]{0,400}?\"bounds\":\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s)
if m: print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)
")
    FOUND="$XY"; break
  fi
  "$HDC" shell uitest uiInput swipe 660 1600 660 750 600 >/dev/null 2>&1; sleep 1.4
done
echo "  保存 → ${FOUND:-未找到}"
if [ -z "$FOUND" ]; then echo "结论：本轮无法验证（保存定位失败），保持挂账"; exit 1; fi
read SX SY <<< "$FOUND"; tap "$SX" "$SY"; sleep 2.5
echo "[7] 回对话页（Settings 的 ‹ 在 93,190）"; tap 93 190; sleep 3; has "↑"
echo "[8] 定位 TextInput 注入「你好」"
XY2=$(dump; DUMP_PATH="$DUMP" python -c "
import json,os,re
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
try: d=json.loads(s,strict=False)
except Exception:
    i=s.rfind(']'); d=json.loads(s[:i+1],strict=False)
out=[]
def w(n):
    if isinstance(n,dict):
        at=n.get('attributes') or {}
        if at.get('type')=='TextInput':
            b=at.get('bounds') or ''
            m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
            if m: out.append(((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2))
        for c in (n.get('children') or []): w(c)
    elif isinstance(n,list):
        for c in n: w(c)
w(d)
print(out[0][0], out[0][1])
")
echo "  输入框 → $XY2"; read IX IY <<< "$XY2"
tap "$IX" "$IY"; sleep 1.2
"$HDC" shell "uitest uiInput inputText $IX $IY '你好'" >/dev/null 2>&1; sleep 1.5
echo "[9] 定位 ↑ Button 并点击"
XY3=$(dump; DUMP_PATH="$DUMP" python -c "
import json,os,re
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
try: d=json.loads(s,strict=False)
except Exception:
    i=s.rfind(']'); d=json.loads(s[:i+1],strict=False)
out=[]
def w(n):
    if isinstance(n,dict):
        at=n.get('attributes') or {}
        if at.get('type')=='Button' and at.get('text')=='↑':
            b=at.get('bounds') or ''
            m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
            if m: out.append(((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2))
        for c in (n.get('children') or []): w(c)
    elif isinstance(n,list):
        for c in n: w(c)
w(d)
print(out[0][0], out[0][1])
")
echo "  ↑ → $XY3"; read UX UY <<< "$XY3"
"$HDC" shell "hilog -r" >/dev/null 2>&1
tap "$UX" "$UY"; sleep 3
shot1="/data/local/tmp/err_1.png"; "$HDC" shell uitest screenCap -p "$shot1" >/dev/null 2>&1
"$HDC" file recv "$shot1" "$EV\\13_error_probe.png" >/dev/null 2>&1; echo "  [证据] 13_error_probe.png"
sleep 4; shot2="/data/local/tmp/err_2.png"; "$HDC" shell uitest screenCap -p "$shot2" >/dev/null 2>&1
"$HDC" file recv "$shot2" "$EV\\13_error_probe2.png" >/dev/null 2>&1; echo "  [证据] 13_error_probe2.png"
"$HDC" shell hilog -x 2>/dev/null | grep -E "send failed|yunkai" | tail -8 > /tmp/hilog_send.txt
echo "[10] hilog 取证："; cat /tmp/hilog_send.txt
dump; echo "[11] 应用存活与状态："; has "↑"
echo "[12] 恢复正确地址"; launch
tap 93 204; sleep 1; tap 1128 366; sleep 3
XY4=$(dump; DUMP_PATH="$DUMP" python -c "
import json,os,re
s=open(os.environ['DUMP_PATH'],encoding='utf-8',errors='replace').read()
try: d=json.loads(s,strict=False)
except Exception:
    i=s.rfind(']'); d=json.loads(s[:i+1],strict=False)
out=[]
def w(n):
    if isinstance(n,dict):
        at=n.get('attributes') or {}
        if at.get('type')=='TextInput':
            b=at.get('bounds') or ''
            m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
            if m: out.append(((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2))
        for c in (n.get('children') or []): w(c)
    elif isinstance(n,list):
        for c in n: w(c)
w(d)
print(out[0][0], out[0][1])
")
read FX FY <<< "$XY4"; tap "$FX" "$FY"; sleep 1.2
"$HDC" shell uitest uiInput keyEvent 2082 >/dev/null 2>&1; sleep 0.5
for i in $(seq 1 45); do "$HDC" shell uitest uiInput keyEvent 2055 >/dev/null 2>&1; done; sleep 1
"$HDC" shell "uitest uiInput inputText $FX $FY 'https://open.bigmodel.cn/api/paas/v4'" >/dev/null 2>&1; sleep 1.5
"$HDC" shell uitest uiInput keyEvent Back >/dev/null 2>&1; sleep 1.5
FOUND=""
for i in 1 2 3 4 5 6 7 8; do
  dump
  if grep -q '"text":"保存"' "$DUMP"; then FOUND="yes"; break; fi
  "$HDC" shell uitest uiInput swipe 660 1600 660 750 600 >/dev/null 2>&1; sleep 1.4
done
if [ -n "$FOUND" ]; then dump; XY=$(DUMP_PATH="$DUMP" python -c "
import re
s=open(r'D:\MyAIWorkspace\project\yunkai\yunkai-harmony\tests\fullflow\_dump.json',encoding='utf-8',errors='replace').read()
m=re.search(r'\"text\":\"保存\"[\s\S]{0,400}?\"bounds\":\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s)
if m: print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)
"); read SX SY <<< "$XY"; tap "$SX" "$SY"; sleep 2; echo "[13] 已恢复并保存"; fi
has "open.bigmodel"
