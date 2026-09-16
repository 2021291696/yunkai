import json
import sys
from pathlib import Path

dump_path = sys.argv[1]
s = Path(dump_path).read_text(encoding="utf-8", errors="replace")
try:
    data = json.loads(s, strict=False)
except Exception:
    i = s.rfind("]")
    data = json.loads(s[: i + 1], strict=False)

vals = []


def w(n):
    if isinstance(n, dict):
        at = n.get("attributes") or {}
        if at.get("type") == "TextInput":
            vals.append(at.get("text") or "")
        for c in n.get("children") or []:
            w(c)
    elif isinstance(n, list):
        for c in n:
            w(c)


w(data)
for v in vals:
    print(v)
