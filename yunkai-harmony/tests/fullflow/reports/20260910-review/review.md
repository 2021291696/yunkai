# 门0 静态审查 — yunkai-harmony（云开鸿蒙版）2026-09-10

## 审查对象与范围

| 项 | 值 |
|---|---|
| 仓库 / HEAD | `D:\MyAIWorkspace\project\yunkai\yunkai-harmony`，branch `main`，`57c6003` |
| 工作区 | 仅 `tests/fullflow/manifest.yaml` 未提交（本轮清单重写），源码 clean |
| 审查口径 | **全量**：`entry/src/main/ets/` 全部 28 个 `.ets`（3057 行，逐文件读完）+ `entry/src/ohosTest/ets/test/Logic.test.ets`（437 行 / 43 用例）|
| 今日变更链路（重点） | `47fb595..HEAD` 共 10 文件：`common/ThemeMode.ets`(新)、`entryability/EntryAbility.ets`、`store/ConfigStore.ets`、`pages/Settings.ets`、`pages/HistoryDrawer.ets`、`pages/Chat.ets`、`pages/CanvasCard.ets`、`pages/Canvas.ets`、`pages/SkillManage.ets`、`README.md` |
| 依据 | `~/.agents/skills/logic-review/references/code-checklist.md` 10 维；项目硬约束取 `AGENTS.md` |
| 附加材料 | SDK 枚举/前置条件实测（`D:\Huawei\DevEcoStudio\sdk\...`）、资源档核对（`base`/`dark` color.json）、兄弟仓 `yunkai-android` 交叉核对 |

**未执行的维度与原因**

- 维度 10 技术选型：N/A —— 今日变更未引入任何新库/新框架（`git diff --stat 47fb595..HEAD` 无 `oh-package.json5`/`build-profile.json5` 改动）。
- hvigor 构建 / hdc 装机 / `aa test` 实跑 / UI 实机验证：**未执行**（按 run-all 分工属门1、门2；本门只做静态审查，不跑环境）。维度 9 的「既有测试仍通过」因此只做到静态核对（用例数 43 = manifest 门1 断言值），未取实跑输出。
- 无新增代码的新技术选型，故未调 `validate-agent`。

## 10 维度结论表

| # | 维度 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | 功能正确性 | **PASS** | 两套同名不同值枚举映射**正确**：`common/ThemeMode.ets:16-18` 用 `ConfigurationConstant.ColorMode.COLOR_MODE_DARK` 判深，`:38` 显式转 ArkUI `ColorMode.DARK` 写入 AppStorage。SDK 实测：`@ohos.app.ability.ConfigurationConstant.d.ts:48/57/66` NOT_SET=-1 / DARK=0 / LIGHT=1；`component/state_management.d.ts:38` ArkUI `LIGHT=0`、`DARK`(隐式=1) —— 两套枚举**恰好相反**，显式转换是必需而非风格选择。启动时序正确：`EntryAbility.ets:34-36` 先读档+应用、`:41` 才 `loadContent`（AppStorage 侧无首帧闪错档）。输出形态唯一口径 `Chat.ets:199-205`（sanitize 后再 detect，防散文夹 `<html` 误判）与入库/回载一致。唯一落库点=真实回答成功后（`Chat.ets:206-214`），取消走代次计数器 + `finally` 条件复位（`Chat.ets:136-137,187,193,235`） |
| 2 | 安全红线 | **FAIL（Important×1，非 blocking）** | PASS 面：无硬编码密钥（`grep -rnE "sk-[A-Za-z0-9]{10,}\|api[_-]?key\s*[:=]\s*['\"]…"` 空）；SQL 全 predicates 无拼接（`MessageRepo.ets:44-48`、`Db.ets:49` 等）；日志不打敏感字段（`LlmClient.ets:87` 的 Authorization 不进日志；`AgentLoop.ets:100-102` 只打 step/model/长度；`SearchClient.ets:142` 打必应响应头 300 字，无 key）；key 输入框 `InputType.Password`（`Settings.ets:272`）。FAIL 面见 I2（`HtmlGuard` 未覆盖内联事件处理器/iframe）|
| 3 | 性能 | **PASS** | 资源释放全覆盖：`http.destroy()` 在 `finally`（`LlmClient.ets:98-100`、`SearchClient.ets:89/115/145`、`BuiltinTools.ets:125-127`），`ResultSet.close()` 全覆盖（`Db.ets:59`、`MessageRepo.ets:62`、`ConversationRepo.ets:35`、`SkillRepo.ets:21/39`）；历史只回传 `plain` 纯文本控上下文（`Chat.ets:171-184` + `MessageRepo.ets:26`）；`List.cachedCount(4)`（`Chat.ets:449`）。已知且已在代码注释披露的 M2 项（`CanvasCard.ets:6-7` List 滚动重建卡顿）不计为本轮新债。Minor 提示：`messages` 表建表无 `conversation_id` 索引（`Db.ets:21-28`），自用数据量无影响，M2 加数据量前补 |
| 4 | 代码质量 | **FAIL（Minor×1）** | 死状态/死方法见 M1（`Chat.ets:51,110-112,226` + `MessageRepo.ets:69-85`）。其余 PASS：逐文件核对无未用导入（如 `HistoryDrawer.ets` 已随历史轮次删掉 `Msg` 导入）；命名与仓库既有风格一致（`glassCard`/`openHistory`/`pickTheme`）|
| 5 | 可维护性 | **PASS** | 魔法数已常量化：`ThemeTokens.ets:4-13`（BLUR/R/EASE）、`AgentLoop.ets:42` MAX_STEPS、`LlmClient.ets:31` MAX_TOKENS、`LlmClient.ets:89` readTimeout 带出处注释；主题档位集中在 `common/ThemeMode.ets`，页面只调 `applyThemeMode`。Minor：`Chat.ets:429` 与画布页/卡硬编码色见 M3 |
| 6 | 架构对齐 | **PASS** | 分层未破：UI 层不直接碰 RDB，全部经 `store/*Repo`；主题链路按 `AGENTS.md`「common/（WallpaperLayer / ThemeMode）」落位，`EntryAbility` 只做编排不写业务；工具契约统一（`AgentTool.ets:8-9` + `BuiltinTools.err` + `AgentLoop.execTool:159-177`）；今日未新增依赖。`SearchRouter` 现役未接是 `AGENTS.md` 已披露的 M2 裁决项，非新债 |
| 7 | 变更风险 | **PASS** | 新增 Preferences 键 `themeMode` 有缺省回落（`ConfigStore.ets:37` → `'system'`），老装机开箱即跟随系统；**关键**：`ConfigStore.save:21-32` 不触碰 `themeMode`/`wallpaper` 键 → 设置页「保存」不会把主题/壁纸重置。新增资源档 `resources/dark/element/color.json` 与 base 键名 16/16 完全一致（无资源缺失回落）；RDB 变更走 `ALTER + 幂等回填`（`Db.ets:36-48`、`48-68`），无破坏性改动。回滚=还原提交，遗留 `themeMode` 键无害 |
| 8 | 需求对齐 | **PASS** | README:14「主题三档（跟随系统/浅色/深色，手动档即时生效并持久化）」逐条对位：三档 UI `Settings.ets:198-228`、持久化 `Settings.ets:64-66` + `ConfigStore.ets:40-44`、即时生效 `ThemeMode.applyThemeMode`。抽屉三条需求对位：标题「会话」`HistoryDrawer.ets:29`、长按提示收编到标题行 `:36`、下半「历史轮次」已删（`git diff 47fb595..HEAD` 显示 `turns/questionOf/onOpenTurn` 全删）。「与 Android 版同步」承诺经兄弟仓核实**属实**：`yunkai-android` `SettingsScreen.kt:132-134` 同名三档 + `ConfigStore.kt:46/78` 同键 `themeMode`、`HistoryDrawer.kt:91`「长按可删除」、`git log c3c3142 / 9ebb808` 同题同日。无 scope creep |
| 9 | 测试覆盖 | **FAIL（Important×1）** | PASS 面：43 用例覆盖 sanitize/detect/三个搜索解析/关键词提取/`pickModel` 双模型+端点守卫/autoRoute 开关/AgentLoop 取消与工具失败回灌/Repo CRUD（`Logic.test.ets:21-435`，`grep -c "^\s*it("` = 43，与 `tests/fullflow/manifest.yaml` 门1 断言 `Tests run: 43` 一致）。FAIL 面见 I3：今日新增的主题链路零用例。未实跑（分工归门1）|
| 10 | 技术选型 | **N/A** | 无新技术选型（未新增依赖/框架）|

## 发现与处置

### blocking

无。

### Important

**I1｜启动路径 `setColorMode` 早于 `loadContent`，且异常被静默吞掉 → 冷启动「深色档」可能半深且零信号**
`entry/src/main/ets/entryability/EntryAbility.ets:36`（配合 `common/ThemeMode.ets:33-37`）
- 事实：`:36` 调 `applyThemeMode`（内部 `ctx.getApplicationContext().setColorMode(cm)`）发生在 `:41` `windowStage.loadContent('pages/Index', …)` **之前**。SDK 对该 API 的前置条件写明「Before calling this API, ensure that the window has been created and the page corresponding to the UIAbility has been loaded (using the loadContent API in onWindowStageCreate)」（`D:\Huawei\DevEcoStudio\sdk\default\openharmony\ets\api\application\ApplicationContext.d.ts:310`，签名 `:326`；`UIAbilityContext.d.ts:1231/1253` 同款措辞）。
- 风险：若该调用在 page 未加载时被忽略或抛错，`$r('app.color.*')` 仍按 `base`（浅色）档解析，而 `ThemeMode.ets:38` 已把 AppStorage `colorMode` 写成 `DARK` → 壁纸亮度滤镜/跳板底色变暗、玻璃卡与文字仍浅（半深）；`ThemeMode.ets:33-37` 的 `catch {}` 不记任何日志，门2 拿不到线索。
- 说明（不确定点）：`setColorMode` 文档只列 401/16000011 两个错误码，本调用未越过 page 加载门槛时**可能**仅是「不触发即时刷新」而非失败；页面在 `:41` 之后才创建，资源解析发生在调用之后，因此也可能实测正常。我无法在不跑设备的情况下判定，故不判 blocking。
- 建议：保留 AppStorage 早写（避免首帧闪正确档），把**资源换档**调用挪到 `loadContent` 回调内（或 `Index.aboutToAppear`）；`catch` 里补 `hilog.warn` 便于门2 取证。
- 实测闸口：`tests/fullflow/manifest.yaml` 门2 步骤 `settings_theme_modes`（`app://restart` 后 `expect_visible 想聊点什么？` + 与浅色截图对比整屏转暗）。**该步失败即先改此处。**

**I2｜`HtmlGuard` 只剥 `<script>`，内联事件处理器 / iframe / `javascript:` URL 仍可在 WebView 执行**
`entry/src/main/ets/service/HtmlGuard.ets:19-36`（消费点 `pages/CanvasCard.ets:56`、`pages/Canvas.ets:63`）
- 事实：`sanitize` 仅做 `<script>…</script>` 块与散标签剥离（`:26-27`），**未**处理 `on*=` 属性、`<iframe>/<object>/<embed>`、`href|src="javascript:"`；两处 Web 渲染点均未设 `javaScriptAccess(false)`、无 `onLoadIntercept`、无 CSP。
- 可达性：待渲染 HTML 由主模型生成，而主模型上下文含 `read_web`/`web_search` 抓回的不可信网页正文（`BuiltinTools.ets:89-128`、`SearchClient`）→ prompt injection 可让模型吐出 `<img src=x onerror=…>`，在 WebView 内执行 JS。data: URL 为不透明源，读不到沙箱、拿不到 key，但可发起外部请求（信标/外发）。
- 现状对照：`AGENTS.md` 的 M2 待办只列了「iframe 剥离」，未覆盖内联事件属性 —— 属已知方向的**未覆盖部分**，非全新技术债。
- 建议：`sanitize` 增补 `on\w+\s*=` 属性、`<iframe>/<object>/<embed>`、`javascript:` 协议剥离；两处 `Web` 显式 `javaScriptAccess(false)`（讲解页无需 JS，与 `HtmlGuard` 注释里的既定立场一致）。

**I3｜今日新增主题链路零单测（清单维度 9 判 FAIL）**
`entry/src/main/ets/common/ThemeMode.ets:16-39`
- 事实：`Logic.test.ets` 未 import `ThemeMode`，43 用例中无一条触及 `configIsDark`/`effectiveDark`/`applyThemeMode`；`ConfigStore.getThemeModeSync` 亦无用例（既有用例只测了 `searchProvider` 回落 `:359-369`）。
- 可测性：`configIsDark` 是纯函数（入参 `ConfigurationConstant.ColorMode | undefined`），最该测的正是「两套枚举相反」这一契约；`effectiveDark` 可复用既有 `AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext()`（`Logic.test.ets:161` 同法）构造 context。
- 不判 blocking 的理由：该链路是配置/资源驱动，门2 步骤 ⑨ 会在模拟器上端到端实测三档；单测补的是「枚举映射契约」这一层。
- 建议补 3 条：① `configIsDark(COLOR_MODE_DARK)===true` 且 `configIsDark(COLOR_MODE_LIGHT)===false`（防有人误用 ArkUI `ColorMode.DARK`=1）；② 手动档时 `effectiveDark` 不读系统值；③ 「跟随系统」档 `AppStorage 'themeMode'` 与 `'colorMode'` 语义一致。

### Minor

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| M1 | `pages/Chat.ets:51,110-112,226`；`store/MessageRepo.ets:69-85` | 死状态/死方法：`@State turns` 在 `build()` 中不被读（只在 `init` 内当临时数组用），`refreshTurns()` 的赋值无人消费，`getHtmlByTurn()` 全仓零调用 —— 均为 `e29f984` 删「历史轮次」后的遗留；`@State turns` 还会在每轮发送后触发一次无谓重渲染 | `turns` 降为 `init()` 内局部变量；删 `refreshTurns()` 与 `getHtmlByTurn()` |
| M2 | `pages/Chat.ets:453-459` + `pages/GuidePage.ets:15,28` | 引导页副标题「已有 N 次对话」恒为 0：`convCount` 是普通 prop，按作者自己在 `Chat.ets:257-258` 认定的「普通 prop 组件创建即定型」，它在首次 build 时读到的 `this.convs` 还是 `[]`（`refreshConvs` 是 `init()` 内在 `await`，`Chat.ets:71,76-79`），之后父组件重渲染不会回填 | 改 `@Prop convCount`（或由 Chat 传 `@State` 派生值）|
| M3 | `pages/Canvas.ets:36-38,42,48,57,71`；`pages/CanvasCard.ets:37,39,49,64-65`；`pages/Chat.ets:429` | 深色档下仍硬编码浅色：画布页 `'#FAF6EF'/'#F2EADCB0'`、画布卡 `'#FFFFFFE6'` 与深色文字不随主题；时间线「✓ 完成」用 `'#4C7A28'` 在深色玻璃底上对比不足 | 属外观一致性（非逻辑），建议 M2 连同画布卡性能一起改走 token |
| M4 | `store/ConfigStore.ets:35-38` | `getThemeModeSync`（Preferences 同步 API）无 try/catch，且位于启动关键路径（`EntryAbility.ets:34`）：一旦抛 BusinessError，`onWindowStageCreate` 中断 → `loadContent` 不执行（黑屏、无提示） | 包 try/catch 回落 `'system'`，与 `load` 的 fallback 语义一致 |
| M5 | `pages/Settings.ets:62-68` | `pickTheme` 先乐观改 `this.themeMode`（`:63`）再等 `setThemeMode(...).then(...)`，无 `.catch`：写入失败时档位高亮已变但既未持久化也未换档（半状态，重启回旧档）；连点两档还存在两次写入完成顺序不确定的竞态 | 补 `.catch`（回滚 `this.themeMode` + toast）；或改为「先写库成功再改状态」 |
| M6 | `pages/Chat.ets:242` + `pages/Canvas.ets:19` | 画布 HTML（上限 300KB，`HtmlGuard.ets:34`）常驻 AppStorage `'canvasHtml'`，从 Canvas 页返回后仍不清理，且进程内下次读到旧值 | Canvas 取走后 `AppStorage.delete`，或让 Canvas 从单一来源读 |
| M7 | `service/HtmlGuard.ets:24,28` | `lower` 在剥 script（`:26-27`）**之前**快照，`:28` 的 viewport 判据用的是旧串：若原文 `<script>` 内出现 `name="viewport"` 字面量，会跳过 viewport 注入（边角；脚本随后已被剥掉） | 把 `lower` 计算后移到剥 script 之后 |
| M8 | `pages/Settings.ets:144-148` | `openSync` 成功而 `copyFileSync` 抛错时，已打开的 src/dst fd 不会关闭（异常路径泄漏，概率低） | try/finally + `closeSync` |

## 结论

**APPROVE**（0 blocking，3 Important，8 Minor）

理由：核心链路（发送管线/取消语义/工具失败收敛/HTML 形态判定/双模型端点守卫/落库与回载一致性）静态核对无逻辑错误，项目硬约束逐条满足或已被代码显式披露（`max_tokens` 16384 `LlmClient.ets:31`、`readTimeout 600000ms` `:89`、`data:base64` 作 src 两处、`loadData` 全仓零出现、必应轨走 `extractKeywords`、工具失败回 `{"error":…}`、key 只进 Preferences、双模型分工只对智谱端点 `AgentLoop.ets:149-156`），主题三档的「两套枚举相反」这一高危点实现正确且唯一收敛在 `ThemeMode`。

建议在门1/门2 前处理：**I1**（改动 1 行调用时机 + 1 行日志，直接决定门2 ⑨ 是否稳定通过）、**M4**（启动路径兜底）、**M1/M2**（顺手清理与修显示）。I2 按 `AGENTS.md` 既有 M2 待办合并推进。
