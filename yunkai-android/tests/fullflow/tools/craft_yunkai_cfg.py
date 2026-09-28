# -*- coding: utf-8 -*-
"""生成 yunkai DataStore preferences_pb（真机测试配套工具）。

场景：adb 装包后、首启前，把模型配置/皮肤/屏幕感知开关预写进 app 私有目录，
免去 UI 键入（MIUI 风控机 input 注入受限时的替代路径；本机「USB 安装」被
小米风控临时限制，只能文件管理器手动装 APK，见 reports/20260925_realdevice_round1）。

用法（宿主机，Git Bash）：
  DEEPSEEK_API_KEY=xxx python craft_yunkai_cfg.py out.preferences_pb [skin] [theme] [blacklist_pkg]
  adb push out.preferences_pb /data/local/tmp/cfg.pb
  adb shell "am force-stop com.zhuolin.yunkai; run-as com.zhuolin.yunkai sh -c \\
    'cat /data/local/tmp/cfg.pb > files/datastore/yunkai_cfg.preferences_pb'"

格式要点（androidx.datastore 1.1.1 实测反编译 PreferencesProto 得出，勿凭记忆）：
  PreferencesMap = repeated entry(field 1)
  entry          = key string(field 1) + Value message(field 2, tag 0x12)
  Value oneof    = boolean 1 / float 2 / integer 3 / long 4 / string 5 / stringSet 6 / double 7 / bytes 8
键名与 ConfigStore.kt 严格一致；apiKey 只经环境变量，禁明文落盘（铁律 2）。
"""
import os
import sys
from pathlib import Path


def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def lp(d):
    return varint(len(d)) + d


def f_str(num, s):
    return bytes([(num << 3) | 2]) + lp(s.encode("utf-8"))


def f_bool(num, b):
    return bytes([num << 3]) + varint(1 if b else 0)


def f_int(num, i):
    return bytes([num << 3]) + varint(i)


def v_str(s):
    return f_str(5, s)


def v_bool(b):
    return f_bool(1, b)


def v_int(i):
    return f_int(3, i)


def v_set(ss):
    return bytes([(6 << 3) | 2]) + lp(b"".join(f_str(1, s) for s in ss))


def entry(k, vbody):
    # entry = field1(key str) + field2(Value msg)；外层 \x0A+lp 只包一次（勿双层包装）
    inner = f_str(1, k) + b"\x12" + lp(vbody)
    return b"\x0A" + lp(inner)


def build(skin, theme, blacklist):
    es = [
        entry("baseUrl", v_str("https://api.deepseek.com/v1")),
        entry("apiKey", v_str(os.environ["DEEPSEEK_API_KEY"])),
        entry("model", v_str("deepseek-chat")),
        entry("autoRoute", v_bool(True)),
        entry("searchProvider", v_str("bing")),
        entry("skinId", v_str(skin)),
        entry("themeMode", v_str(theme)),
        entry("screenSense", v_bool(True)),
        entry("maxSteps", v_int(25)),
    ]
    if blacklist:
        es.append(entry("screenBlacklistUser", v_set([blacklist])))
    return b"".join(es)


def verify(d):
    i, keys = 0, []
    while i < len(d):
        assert d[i] == 0x0A, f"outer tag @{i}"
        ln = d[i + 1]
        i += 2
        sub = d[i:i + ln]
        i += ln
        assert sub[0] == 0x0A, "key field missing"
        klen = sub[1]
        keys.append(sub[2:2 + klen].decode())
        rest = sub[2 + klen:]
        assert rest[0] == 0x12, f"value field missing for {keys[-1]}"
    return keys


def main():
    out = os.path.normpath(os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "yunkai_cfg.preferences_pb"))
    skin = sys.argv[2] if len(sys.argv) > 2 else "clear"
    theme = sys.argv[3] if len(sys.argv) > 3 else "dark"
    blacklist = sys.argv[4] if len(sys.argv) > 4 else ""
    data = build(skin, theme, blacklist)
    keys = verify(data)
    Path(out).write_bytes(data)
    print("wrote", out, len(data), "bytes; keys:", ",".join(keys))


if __name__ == "__main__":
    main()
