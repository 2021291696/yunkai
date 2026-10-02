# run-all 全量逻辑审查 + 三门验收 — 云开桌面 agent（双模型版）

运行：2026-09-08｜平台：模拟器 127.0.0.1:5555｜代码库：2865 行 ets｜HEAD：fb3c9dc
范围：M1 全量（用户点名全量逻辑审查一道 + run-all）

## 门0 全量逻辑审查（10 维，logic-review --full 口径）

**结论：PASS**（1 Important 已修，0 blocking，7 Minor 归档）

| 维度 | 结论 | 关键证据 |
|------|------|---------|
| 1 功能正确性 | PASS | AgentLoop 取消三检查点/pickModel 单点/净空落库唯一成功路径；LoopResult 无 isHtml（口径唯一在 Chat 消费方） |
| 2 安全红线 | PASS | key 不落日志；全参数化 SQL（RdbPredicates）；script 剥离在位；read_web 内网 SSRF 面 Minor 记 M2（自用威胁模型低） |
| 3 性能 | PASS | List cachedCount(4)；MessageRepo.add O(n) maxTurn、串行工具——Minor 记 M2 |
| 4 代码质量 | PASS | 无死代码（PromptBuilder 已删）；T4 helper public 等 3 项风格 Minor 记 M2 |
| 5 可维护性 | PASS | MAX_STEPS/MAX_TOKENS/截断长度全常量化；M2 待办注释块在位 |
| 6 架构对齐 | PASS | 分层遵守（UI 不碰 DB）；双模型 pickModel 单点收敛 |
| 7 变更风险 | PASS | schema v2 ALTER try-catch+backfill 幂等；ConfigStore 新键 fallback 无损 |
| 8 需求对齐 | PASS | 定案 10 条逐条有落点；无 scope creep（M2 项全注释标记） |
| 9 测试覆盖 | PASS | logicTest 43/43（新增 pick_model_non_zhipu_endpoint_falls_back 回归） |
| 10 技术选型 | N/A | 无新技术选型 plan |

### 审查发现与处置

- **[Important] pickModel 无端点守卫**（AgentLoop.ets:140）：longModel 默认 'glm-4.7'，非智谱端点用户 use_skill 后切 4.7 会 404 整轮失败 → **已修 fb3c9dc**：baseUrl 含 bigmodel/zhipu（不区分大小写）才允许切换，否则回退主模型；新增回归用例。代价：自建反代域名不含关键词会被保守回退（不切不炸）。
- [Minor×7 归档 M2]：①kind 列写入但 init 重新 detect（冗余无害，注释口径待统一）②错误轮 user 气泡重启后消失（净空语义固有权衡，toast 已告知）③read_web 私网 SSRF 面 ④MessageRepo.add O(n) maxTurn ⑤T4 helper public ⑥SkillManage async onClick 无 catch ⑦CANVAS_HTML_KEY 双处定义+读后不清。

## 门1 构建/单测：PASS

双包 BUILD SUCCESSFUL；logicTest **43/43**（42+新增端点守卫回归用例；模拟器 aa test 实跑）。

## 门2 AI 驱动 UI（双模型增量复验）

| 路径 | 结论 | 证据 |
|------|------|------|
| @eli5 双模型链路（守卫后回归） | **PASS** | h1.jpeg：5.3-flash 调度 use_skill → 切 4.7 长文 → 画布渲染「黑洞是怎么形成的」（目录 6+ 章节：什么是黑洞/恒星的一生/燃料烧完了/巨大的坍塌/引力变得超强/事件视界）——守卫未误伤智谱直连 |
| 普通问答→气泡 | PASS（引用 d2/g12 证据，本轮无相关改动） | 请假短信三版本文本气泡 |
| 自主 web_search | PASS（引用 g28/g29 证据：7 次搜索+读网页+真实新闻汇总） | 提示词诱导+searchProvider fallback 修复后行为 |

此前 M1 七路径证据（tests/fullflow/reports/20260907-agent-m1-gate2/）继续有效——本轮增量改动（pickModel 守卫）仅影响模型选择单点，已回归。

## 三门结论

| 门 | 结论 |
|---|------|
| 门0 全量逻辑审查 | **PASS**（1 Important 修复 fb3c9dc；7 Minor 归档 M2） |
| 门1 构建/单测 | **PASS**（43/43） |
| 门2 UI 增量复验 | **PASS**（双模型链路守卫后回归完好） |

**M1（含双模型分工）交付收口。**
