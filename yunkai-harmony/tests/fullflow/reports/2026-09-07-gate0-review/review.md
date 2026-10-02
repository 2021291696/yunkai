# 门0 静态审查报告 — eli5_harmony（2026-09-07）

范围：entry/src/main/ets 全量 14 文件 1557 行（+ohosTest）。
审查口径：logic-review 精神（正确性/资源/安全/可维护性），聚焦未被单测与 E2E 覆盖的路径。

## 结论：PASS（0 blocking，3 warning）

## Blocking
无。

## Warning（披露后继续）

1. **W1 API key 明文存 Preferences**（ConfigStore.ets）：设备沙箱内明文。MVP 接受（spec 定案"只存设备本地"）；后续可迁 Asset Store Kit（API 26 支持）。
2. **W2 send() 异常 toast 后 user 消息已入库**：失败轮会留"无回答"的会话。属产品设计灰区，不修（用户重试即可补上）。
3. **W3 Db 单例无失效机制**：进程级单例，模拟器/真机长运行无影响；仅测试环境热重载可能持旧连接。

## 已核实要点

- 取消竞态：genId 代次计数器，finally 条件复位，过期结果丢弃 ✓
- 非法输入：空输入/未配置/非 HTML（重试一次）/HTTP≠2xx 均有分支 ✓
- 资源释放：http.destroy() finally、ResultSet close() ✓
- 密钥：不进仓库（.gitignore+grep 复核过）✓
- UI：字色/对比度玻璃化后截图验证 ✓
