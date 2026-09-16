#!/usr/bin/env bash
# yunkai-harmony 门2（界面层）驱动脚本 —— AI 驱动通道用（hdc + uitest）
#
# 用法：bash drive_harmony.sh <证据目录> <步骤...>
#   证据目录 = tests/fullflow/reports/<时间戳>_ui/artifacts/agent-ui/<runID>
#   步骤名见文件末尾 usage 段
#
# 平台假设：模拟器 127.0.0.1:5555、屏 1320x2232；聊天输入框 (585,1944) / 发送 (1199,1944) / 左上 ☰ (93,204) / 抽屉 ⚙ (1128,366)
#   —— 换分辨率要改这几个固定坐标（其余由 dumpLayout 动态解析）
#
# 平台坑（本轮实测，改动本脚本前先读）：
#  1) `uitest uiInput inputText <x> <y> '<文本>'` 支持中文但**不吃换行** → 多行走「逐行 inputText + keyEvent 66」
#  2) 打字后 IME 挡住滚动 → 先 `keyEvent Back` 收键盘再 swipe，否则折叠下方按钮（设置页「保存」）永远定位不到
#  3) `screenCap` 走同一路径会**留旧图**（`file recv` 照样成功 → 拿到上一帧）→ 每次截图用唯一文件名
#  4) dumpLayout 只含屏上节点且长文本会缺；`snapshot_display` 失败会留旧图（用 screenCap）
#  5) 息屏时 dump 全是锁屏节点、截图全黑 → 先 `power-shell wakeup` + `setmode 602`
#  6) 首启慢：`aa start` 回 successfully 不代表可见，要等 8-10s
#  7) hdc 不在 PATH；本地路径给 hdc 一律 Windows 反斜杠（MSYS_NO_PATHCONV 下 `/tmp` 会被当设备路径）
export MSYS_NO_PATHCONV=1
HDC="D:/Huawei/DevEcoStudio/sdk/default/openharmony/toolchains/hdc.exe"
BUNDLE="com.zhuolin.yunkai"

EV_WIN="${1:?用法: bash drive_harmony.sh <证据目录> <步骤...>}"
shift
EV="$(printf '%s' "$EV_WIN" | tr '/' '\\')"
EV_LOCAL="$(printf '%s' "$EV_WIN" | sed 's|^\([A-Za-z]\):|/\L\1|' | tr '\\' '/')"
DUMP_WIN="$(printf '%s' "$EV_WIN" | tr '/' '\\')\\_dump.json"   # 设备侧 dump 落盘位（Windows 反斜杠：adb/hdc file recv 才认）
DUMP="$DUMP_WIN"
mkdir -p "$EV_LOCAL"

tap() { "$HDC" shell uitest uiInput click "$1" "$2" >/dev/null 2>&1; sleep 2.5; }
key() { "$HDC" shell uitest uiInput keyEvent "$1" >/dev/null 2>&1; sleep 2; }
wake() { "$HDC" shell power-shell wakeup >/dev/null 2>&1; "$HDC" shell power-shell setmode 602 >/dev/null 2>&1; sleep 2; }
launch() { "$HDC" shell aa force-stop "$BUNDLE" >/dev/null 2>&1; sleep 1; "$HDC" shell aa start -a EntryAbility -b "$BUNDLE" >/dev/null 2>&1; sleep 9; }
dump() { # dumpLayout 滚动动画期会静默失败而 file recv 留旧图（坑4）——md5 不变就重拉，最多 4 次
  local old="" new=""
  [ -f "$DUMP_WIN" ] && old=$(md5sum "$DUMP_WIN" 2>/dev/null | cut -d' ' -f1)
  for k in 1 2 3 4; do
    "$HDC" shell uitest dumpLayout -p /data/local/tmp/d.json >/dev/null 2>&1
    "$HDC" file recv /data/local/tmp/d.json "$DUMP_WIN" >/dev/null 2>&1
    new=$(md5sum "$DUMP_WIN" 2>/dev/null | cut -d' ' -f1)
    if [ -n "$new" ] && [ "$new" != "$old" ]; then return 0; fi
    sleep 1
  done
}
walk() { DUMP_PATH="$DUMP_WIN" MODE="$1" ARG="$2" python -c "
import json, os, re, sys
s = open(os.environ['DUMP_PATH'], encoding='utf-8', errors='replace').read()
try:
    data = json.loads(s, strict=False)
except Exception:
    i = s.rfind(']'); data = json.loads(s[:i+1], strict=False)
mode = os.environ['MODE']; arg = os.environ['ARG']
texts = []; hits = []
def walk_node(n):
    if isinstance(n, dict):
        at = n.get('attributes') or {}
        t = at.get('text') or ''; ty = at.get('type') or ''
        if t.strip(): texts.append(t)
        if mode == 'text' and arg in t: hits.append(at.get('bounds'))
        if mode == 'type' and ty == arg: hits.append(at.get('bounds'))
        for c in (n.get('children') or []): walk_node(c)
    elif isinstance(n, list):
        for c in n: walk_node(c)
walk_node(data)
if mode == 'list': print('   ' + ' | '.join(texts[:20])); sys.exit()
if mode == 'count': print(sum(1 for t in texts if arg in t)); sys.exit()
if hits:
    import re as _re
    for _b in hits:
        _m = _re.match(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', _b)
        if _m and int(_m.group(3)) - int(_m.group(1)) > 0 and int(_m.group(4)) - int(_m.group(2)) > 0:
            print((int(_m.group(1))+int(_m.group(3)))//2, (int(_m.group(2))+int(_m.group(4)))//2)
            break
"; }
texts() { walk list; }
count() { walk count "$1"; }
expect() { C=$(count "$1"); if [ "$C" != "0" ]; then echo "  PASS 见到「$1」（$C 处）"; else echo "  FAIL 未见「$1」"; fi; }
absent() { C=$(count "$1"); if [ "$C" = "0" ]; then echo "  PASS 确认无「$1」"; else echo "  FAIL 仍出现「$1」（$C 处）"; fi; }
xy_of() { walk text "$1"; }
xy_type() { walk type "$1"; }
tap_text() { XY=$(xy_of "$1"); if [ -z "$XY" ]; then echo "  tap_text FAIL「$1」"; return 1; fi; echo "  tap_text「$1」→ $XY"; tap $XY; }
tap_text_scroll() { for i in 1 2 3 4 5 6 7 8; do XY=$(xy_of "$1"); if [ -n "$XY" ]; then echo "  tap_text_scroll「$1」→ $XY (第${i}次定位)"; tap $XY; return 0; fi; "$HDC" shell uitest uiInput swipe 660 1700 660 700 700 >/dev/null 2>&1; sleep 1.3; done; XY=$(xy_of "$1"); if [ -n "$XY" ]; then echo "  tap_text_scroll「$1」→ $XY (末次定位)"; tap $XY; return 0; fi; echo "  FAIL 找不到「$1」"; return 1; }
shot() { R="/data/local/tmp/shot_$(date +%H%M%S)_$RANDOM.png"; "$HDC" shell uitest screenCap -p "$R" >/dev/null 2>&1; "$HDC" file recv "$R" "$EV\\$1" >/dev/null 2>&1
  F="$EV_LOCAL/$1"; if [ -f "$F" ] && head -c 4 "$F" | od -An -tx1 | tr -d ' \n' | grep -qi "89504e47"; then echo "  [证据] $1 OK"; else echo "  [证据] $1 FAIL"; fi; }
input_chat() { XY=$(xy_type TextInput); [ -z "$XY" ] && XY=$(xy_type TextArea); [ -z "$XY" ] && { echo "  找不到输入框"; return 1; }
  echo "  输入框 → $XY"; tap $XY; sleep 1; "$HDC" shell "uitest uiInput inputText $XY '$1'" >/dev/null 2>&1; sleep 2; key Back; sleep 1; }
send_chat() { tap_text '↑'; }
to_settings() { tap 93 204; sleep 1; tap 1128 366; sleep 3; }
edit_field() { read FX FY <<< "$1"; "$HDC" shell uitest uiInput click $FX $FY >/dev/null 2>&1; sleep 1.2
  "$HDC" shell uitest uiInput keyEvent 2082 >/dev/null 2>&1; sleep 0.5
  for i in $(seq 1 42); do "$HDC" shell uitest uiInput keyEvent 2055 >/dev/null 2>&1; done; sleep 1
  "$HDC" shell "uitest uiInput inputText $FX $FY '$2'" >/dev/null 2>&1; sleep 1.5
  "$HDC" shell uitest uiInput keyEvent Back >/dev/null 2>&1; sleep 1.5; }   # 关键：收键盘，否则后面 swipe 落在键盘上

s6_guide() { wake; launch; dump; echo "⑥ 引导页："; texts; shot 06_launch_guide.png; expect '想聊点什么？'; }
s7_drawer() { wake; launch; tap 93 204; dump; echo "⑦ 抽屉："; texts; shot 07_drawer_layout.png
  for bad in 会话与历史 历史轮次 还没有讲解记录 收起; do absent "$bad"; done; }
s8_dismiss() { wake; launch; tap 93 204; tap 1128 216; dump; absent '长按可删除'
  tap 93 204; tap 1300 1200; dump; absent '长按可删除'
  tap 93 204; key Back; dump; absent '长按可删除'; expect '想聊点什么？'; shot 08_dismiss.png; }
s9_theme() { wake; launch; to_settings; dump; echo "⑨ 设置页（浅）："; texts; shot 09a_light.png
  tap 1143 489; sleep 2; echo "⑨ 点深色后："; dump; texts; shot 09b_dark.png
  launch; echo "⑨ 冷启动后（系统浅 + 应仍深色）："; dump; texts; shot 09c_persist.png
  to_settings; tap 765 489; sleep 2; echo "⑨ 切回跟随系统："; dump; texts; shot 09d_system.png; }
s10_import() { wake; launch; to_settings; tap_text_scroll '技能库'; sleep 3; dump; echo "⑩ 技能库："; texts; shot 10a_skills.png
  tap_text_scroll '粘贴导入'; sleep 3; XY=$(xy_type TextArea); echo "  导入框 → $XY"; tap $XY; sleep 1
  "$HDC" shell "uitest uiInput inputText $XY '# 全流程导入技能'" >/dev/null 2>&1; sleep 2; key Back; sleep 1
  tap_text_scroll '解析并导入'; sleep 3; dump; echo "⑩ 导入后："; texts; shot 10b_imported.png; expect '全流程导入技能'; absent '解析并导入'; }
s11_bare() { wake; launch; input_chat '用一句话回答：1+1等于几？'; dump; echo "⑪ 输入后："; texts; send_chat; sleep 30; dump; echo "⑪ 回答："; texts; shot 11_bare.png; }
s12_canvas() { wake; launch; tap_text '@eli5 讲讲黑洞'; sleep 170; dump; echo "⑫ 画布卡："; texts; shot 12a_card.png; tap_text '画布 · 点此全屏查看'; sleep 5; shot 12b_canvas.png; }
s13_badurl() { wake; launch; to_settings; edit_field '660 1043' 'https://invalid.example.invalid/v1'
  # 保存后 app 自动回对话页（无需再点返回）；错误提示是 4s Toast——连拍抓帧
  tap_text_scroll '保存'; sleep 3; input_chat '你好'; send_chat
  for i in 0 1 2 3 4 5 6 7; do shot "13t_0$i.png"; sleep 1.3; done
  dump; echo "⑬ 错误态（机制证据用 hilog 的 send failed 行核对）:"; texts
  to_settings; edit_field '660 1043' 'https://open.bigmodel.cn/api/paas/v4'; tap_text_scroll '保存'; sleep 2
  to_settings; dump; expect 'open.bigmodel'; shot 13_restored.png; }
# 注意：edit_field 的清空依赖 keyEvent 2082+2055，在中文 composing 态会失效——
# 若字段值越改越乱，先点「中/英」切英文模式再 edit_field（20260911 门2 实测）

usage() { echo "可用步骤：6 7 8 9 10 11 12 13（对应 manifest 的 ui 步骤）"; }
[ $# -eq 0 ] && { usage; exit 0; }
for st in "$@"; do case "$st" in
  6) s6_guide;; 7) s7_drawer;; 8) s8_dismiss;; 9) s9_theme;; 10) s10_import;;
  11) s11_bare;; 12) s12_canvas;; 13) s13_badurl;; *) echo "未知步骤 $st"; usage;; esac; done
