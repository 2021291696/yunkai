# 门0 全量逻辑审查报告 — yunkai-harmony

- 日期：2026-09-14
- 审查人：门0 全量审查（run-all 三门之第一门）
- 模式：全量（非 diff），只审不修
- 范围：`entry/src/main/ets/` 全部 42 个 ArkTS 文件 + `entry/src/main/module.json5` + `resources/rawfile`（skill_eli5.md / vectors 三份用例 / fonts）+ 单测 `entry/src/ohosTest/ets/test/{Logic,ThemeMode,Ability,List}.test.ets`
- 增审：近三天 git log（忆枢系列 69c3a45/3d82cbd/114c984/66188e8、双皮肤 a697b3f/1423ba3、质量轮 66188e8）逐文件细读，并与孪生仓 yunkai-android 抽查 8 处语义一致性
- 依据：`logic-review` SKILL 10 维清单 + 项目 `AGENTS.md`「硬约束（违反即出坑）」逐条对照

---

## 一、总结论

## **PASS-with-warnings**

0 条 blocking、3 条 important、12 条 minor。全部硬约束逐条验证通过（见下表）；发现的问题均不阻断门0，但 important #1（续跑链路摘要缺失）是双端语义分歧，建议在下一轮质量轮双端对齐。

### 硬约束逐条验证（7/7 PASS）

| 硬约束 | 结论 | 证据 |
|--------|------|------|
| 皮肤/主题只走 `applySkinAndTheme` 单点 | PASS | `EntryAbility.ets:75,90`（loadContent 前后各一次，09-10 修复保持）；`Settings.ets:91-104` pickTheme/pickSkin 均经单点；`onConfigurationUpdate`（EntryAbility.ets:106-114）只调 `applySkinTokens`，全仓 grep `setColorMode` 仅 ThemeMode.ets:36 一处（applyThemeMode 内，非回调路径） |
| onConfigurationUpdate 禁再调 setColorMode（递归） | PASS | EntryAbility.ets:106-114 仅重算 token；ThemeMode.ets:44-49 `applySkinTokens` 注释明示禁令 |
| ArkTS TDZ（常量先于引用） | PASS | Schemes.ets:22-23 SKIN_CLEAR/SKIN_AURORA 在类**前**声明，类字段默认值用字面量（1423ba3 修复已固化，行 26-29 注释留痕） |
| WebView 一律 data:base64 作 src | PASS | CanvasCard.ets:27-28,56 与 Canvas.ets:25-26,63 均 `data:text/html;base64`；两处 `javaScriptAccess(false)` 双保险；无 loadData 残留 |
| max_tokens 显式 16384 | PASS | LlmClient.ets:44 `MAX_TOKENS=16384`，buildRequestBody:82 恒写；单测钉死 Logic.test.ets:119 `"max_tokens":16384`；Android 同值 LlmClient.kt:41 |
| LlmClient readTimeout ≥600s | PASS | LlmClient.ets:123（chatMessage）与 :307（chatStream）均 600000；Android LlmClient.kt:50 600s |
| 工具失败一律 `{"error":...}` 不抛出 | PASS | AgentLoop.execTool:289-294 try/catch 收敛 + 未知名/长文拦截均 error JSON；BuiltinTools/M2Tools/MemoryTools 三处工厂全部 try/catch 包裹 |
| 必应轨走 extractKeywords | PASS | SearchClient.ets:30 仅 bing 分支强制提取 |
| API key 只存本地 Preferences | PASS | ConfigStore 全程 Preferences；全仓无硬编码密钥（grep 无命中） |
| eli5 配方双端一致 | PASS | md5 实测两端 `skill_eli5.md` 相同（f16abe7d...） |

### 测试覆盖

`Logic.test.ets` 实测 77 个用例（`grep -c '^\s*it('` = 77），覆盖：HtmlGuard 新增的内联 on*/javascript:/iframe 三面（门0 上轮披露项已补测）、pickModel 端点守卫 3 例、软预算 3 例（含 keep==0 短文案）、stripTraceForPersist 2 例、Summarizer 窗口/触发/跨度 9 例、BM25/Privacy/Migrator 协议向量回放、记忆常量钉死。ThemeMode 2 例（上轮「零单测」披露已修复）。**未覆盖**：AgentLoop seedMessages 续跑分支（system 就地重建）、MemoryStore.hasLegacyRows / conversationSearch（RDB 层记忆接口无直接用例）。

---

## 二、发现清单

### important（3 条）

#### I-1 续跑链路不触发会话摘要（双端语义分歧）

- 位置：`entry/src/main/ets/pages/Chat.ets:441-519`（resumeTask）
- 问题：Android `ChatViewModel.kt:295` 在 resumeTask 成功落库后调用 `maybeSummarize(cfg)`，注释明说「resume 续跑同样走摘要检查（长收尾回答恰易触发阈值）」；鸿蒙 resumeTask 收尾只有 `refreshTurns()` + `refreshConvs()`，**没有** maybeSummarize。续跑式长任务（恰恰是最容易连续多轮到顶的场景）在鸿蒙端永远不会推进摘要换出，窗口无界增长撑大上下文。
- 证据：
  ```kotlin
  // Android ChatViewModel.kt:294-295（resumeTask 内）
  loadTurns()
  // 忆枢 M2：resume 续跑同样走摘要检查（长收尾回答恰易触发阈值）
  maybeSummarize(cfg)
  ```
  鸿蒙侧 Chat.ets resumeTask 成功路径（:490-501）无对应调用；send() 成功路径（:390）有。
- 建议：resumeTask 成功落库后补 `await this.maybeSummarize(cfg);`（与 send 同语义，失败静默）。

#### I-2 设置页「自动保存」对 Radio/Toggle 不生效，切完即走会静默丢失

- 位置：`entry/src/main/ets/pages/Settings.ets:490-503`（搜索源三个 Radio）、`:527-529`（技能自动路由 Toggle）
- 问题：文本输入/模型下拉的 onChange 都挂了 `this.autoSave()`（500ms 防抖落盘），但搜索源 Radio 的 onChange 只写 `this.searchProvider`、autoRoute Toggle 只写 `this.autoRoute`，均不触发 autoSave；`aboutToDisappear()`（:82-88）只在 saveTimer 挂起时兜底冲刷。用户仅切换搜索源或路由开关后按返回，改动**不落盘**（除非之后碰过任何文本框或点了「保存」）。与本页「自动保存（产品动机就是防丢）」的自洽承诺相悖。
- 证据：`:493` `.onChange((on: boolean) => { if (on) { this.searchProvider = 'bing'; } })` — 无 autoSave；`:529` `.onChange((on: boolean) => { this.autoRoute = on; })` — 无 autoSave。
- 建议：两处 onChange 补 `this.autoSave()`（或改选中即写）；注意 autoSaveNow 以已存配置为底合并，语义安全。

#### I-3 自定义壁纸换后返回对话页不生效，与 toast 承诺相悖

- 位置：`entry/src/main/ets/common/WallpaperLayer.ets:19-24`、`entry/src/main/ets/pages/Settings.ets:222-224`
- 问题：WallpaperLayer 仅在 `aboutToAppear` 异步读一次 `getWallpaper` 写入 `@State customUri`；设置页经 `router.pushUrl` 进入（Chat 在栈内不销毁），`router.back` 返回后 Chat/WallpaperLayer 实例不重建、`aboutToAppear` 不再执行 → 壁纸维持旧值。而 Settings.ets:224 toast 明确承诺「已更换，返回对话页生效」。实际要等新开/切换会话（replaceUrl 重建 Chat）或重启才刷新。皮肤/主题切换不受影响（走 AppStorage 'scheme' 响应式），唯独 customUri 是一次性读。
- 建议：WallpaperLayer 订阅一个 AppStorage 键（如换壁纸时 `AppStorage.setOrCreate('wallpaperUri', ...)`），或 Settings 返回前通知刷新。

### minor（12 条，此处仅列清单，细节见各行）

1. `service/AgentLoop.ets:189` — 软预算 keep==0 时 use_skill 输出被替换为「预算已耗尽」短文案，不以 `{"error"` 开头 → 仍会置 longFormActive=true 并撤下记忆段，而模型实际没拿到说明书。**双端同构**（Android AgentLoop.kt:196 同序同语义），属共享边缘瑕疵；建议双端同修（预算占位文案不触发形态切换）。
2. `memory/MemoryStore.ets:56-57` — getCoreBlock 里 `pred`（RdbPredicates）构建后未使用（实际走 querySql），死代码。
3. `memory/MemoryTools.ets:144` vs `:110/:187` — core_memory_replace 的错误文案对未知挡位做了 `strict` 归一化显示，append/archival_insert 未归一 → 同类错误文案口径不一致。
4. `pages/MemoryManage.ets:200` — 归档搜索框每键触发 `reload()`（全表 allArchival），无防抖，且多次 async reload 无序号防护，理论上旧响应可覆盖新结果（本地小数据影响轻）；另 `:25` `editBlock` @State 赋值后无任何消费方。
5. `pages/Index.ets:1-2` — 头注释文字损坏（「aboutToAppDevice Manager / ear 直接 replaceUrl」），疑似编辑事故残留。
6. `service/SearchRouter.ets` + `model/Types.ets:96` NetMode — 全仓零引用（AGENTS 已注明「现役但未接，M2 裁决」，有意保留；单测仍覆盖其纯函数），死代码带文档，可接受，建议留注释标明保留原因（已有）。
7. `pages/Canvas.ets` / `pages/CanvasCard.ets` — 仍为 eli5 时代硬编码暖色（#F2EADCB0/#FAF6EF/#B25E00 等），未迁 Schemes token；深色模式下全屏画布页恒浅色。$r 颜色残留为零（全仓唯一 `$r('app.color.*')` 出现在 ThemeMode.ets 注释里），系统能力（start_window_background）保留 $r 符合设计。
8. `entry/src/test/List.test.ets` + `LocalUnit.test.ets` — 模板默认用例残留（assertContain 等），与门1 的 `logicTest` 过滤无关，属脚手架未清理。
9. `AGENTS.md:9` — 单测计数写「忆枢后 72/72」，66188e8 已增至 77（commit message 与代码一致），文档滞后。
10. `service/HtmlGuard.ets:34` — javascript: 伪协议检测匹配字面量，HTML 实体编码形（`&#106;avascript:`）可穿透；因两处 WebView 均 `javaScriptAccess(false)`，实际攻击面不成立，仅作纵深记录。
11. `store/Db.ets:9-73` — 冷启动并发首调 `Db.get` 无单飞（in-flight promise 复用），可能重复 getRdbStore/重复跑 backfillKinds（SQLite 幂等，实际低危）；backfillKinds 为 N+1 逐行 update（一次性迁移可接受）。
12. `pages/Chat.ets:184-190` sendQueuedNow — cancelLoading 不清 `streamText`，随后 send() 起的新一轮在首个增量到达前会短暂渲染上一轮被取消的残文（普通取消/失败路径由 finally 清空，无此问题）。

---

## 三、双端一致性抽查结论（yunkai-android）

抽查 8 处，7 处一致、1 处分歧：

| # | 语义点 | 鸿蒙 | Android | 结论 |
|---|--------|------|---------|------|
| 1 | stripTraceForPersist（占位文案、仅替换 content_parts、无 parts 快路径） | Summarizer.ets:99-122 | ChatViewModel.kt:42-48 | 一致 |
| 2 | applyToolBudget：30000 软预算、keep>0 截断+标记、keep==0 固定短文案 | AgentLoop.ets:53,261-268 | AgentLoop.kt:53,187-194 | 一致 |
| 3 | pickModel 端点守卫（仅 baseUrl 含 bigmodel/zhipu 才切 longModel，longModel 空回退主模型） | AgentLoop.ets:249-256 | AgentLoop.kt:243-245 | 一致 |
| 4 | 摘要阈值 12 轮 / 16000 字 / 单次 ≤300 字 | Summarizer.ets:25-27（+单测钉死） | Summarizer.kt:14-16 | 一致 |
| 5 | 迁移幂等护栏 hasLegacyRows（legacy-m2 已存在则跳写、仅完成改名） | MemoryStore.ets:287-298 + EntryAbility.ets:49-52 | YunkaiApp.kt:54-56 + MemoryStore.kt:59 | 一致 |
| 6 | startEdit 双防护（截断点入摘要跨度清摘要：≤边界 assistant 行 id；清 task_state） | Chat.ets:195-217 | ChatViewModel.kt:324-349 | 一致 |
| 7 | max_tokens 16384 / readTimeout 600s | LlmClient.ets:44,123,307 | LlmClient.kt:41,50 | 一致 |
| 8 | resumeTask 成功后 maybeSummarize | **缺失**（Chat.ets:490-501） | ChatViewModel.kt:295 | **分歧 → I-1** |

附：eli5 配方 md5 双端全等（f16abe7d180f479a7ed401b766f5ee2d）。

---

## 四、维度执行情况（logic-review 10 维）

- 执行：1 功能正确性（AgentLoop 循环/取消/到顶/软预算/续跑、摘要窗口边界、迁移幂等逐条验）｜2 安全红线（密钥/注入面/WebView/沙箱路径 safeJoin 拒 .. 与绝对路径）｜3 性能（MemoryStore 全表检索符合协议本地量级、WallpaperLayer 辉光非逐帧重建）｜4 代码质量｜5 可维护性｜6 架构对齐（分层、皮肤单点、唯一落库口径）｜7 变更风险（Db schema ALTER 容错迁移）｜8 需求对齐（AGENTS 硬约束与当前状态逐条）｜9 测试覆盖
- 未执行：10 技术选型（本仓无新技术选型 plan，不适用）
- 内容审查（6 维）：非本门对象（run-all 门0 为代码审查），未执行

## 五、关键 PASS 证据摘录

- 门1 单测体量：`grep -c '^\s*it(' Logic.test.ets` = 77；`memory_constants_pinned`（:738-748）钉死 BM25/摘要/迁移常量。
- max_tokens 钉死：Logic.test.ets:119 `expect(body.includes('"max_tokens":16384')).assertTrue()`。
- 递归禁令落实：EntryAbility.ets:104-105 注释 + 实现仅 applySkinTokens。
- WebView 双保险：CanvasCard.ets:58 / Canvas.ets:65 `javaScriptAccess(false)`。
- 取消不烧 token：AgentLoop.ets:210-214 收尾 LLM 调用前 checkCancel 先抛（有专项单测 `loop_cancel_during_last_exec_skips_wrapup_call`）。
- 删会话清孤儿：Chat.ets:647-657 doDelete 先 deleteTaskState 再 remove（66188e8 项）。
