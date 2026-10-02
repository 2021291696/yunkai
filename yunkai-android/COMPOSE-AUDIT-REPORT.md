# Jetpack Compose Audit Report — yunkai-android

- **日期**: 2026-09-30
- **审计对象**: `D:\MyAIWorkspace\project\yunkai\yunkai-android`（单 app 模块，~8.8k 行 Kotlin）
- **Skill 版本**: jetpack-compose-audit 4.3.2 · Kotlin 2.0.21 / Compose BOM 2024.12.01（**Strong Skipping 默认开启**）
- **总体评分**: **67 / 100** — Needs work（集中在一个屏：聊天主界面流式渲染热路径）

## Scorecard

| 类别 | 得分 | 状态 | 权重 |
|------|------|------|------|
| Performance | 6/10 | needs work | 35% |
| State management | 7/10 | solid | 25% |
| Side effects | 7/10 | solid | 20% |
| Composable API quality | 7/10 | solid | 20% |
| **Overall** | **67/100** | | |

**置信度: High** — 全部 13 个 UI 文件逐一读过或抽查，编译器报告已生成。

## Critical Findings

### C1. 流式输出期间整页高频重组 + O(n) markdown 全量重解析
- **证据**: `ChatViewModel.kt:498` 每个 SSE chunk 直写 `streamText.value`（无节流）；`ChatScreen.kt:209` 在组合体内执行 `renderMarkdownSingle(vm.streamText.value)`，无 `remember` 缓存；`ChatScreen.kt:660` thinking 文本每 chunk `takeLast(600)` 切串。LLM 流式 10–30 chunk/秒 → 每秒几十次全树重组，且 markdown 解析成本随回答变长线性增长（累积 O(n²)）。
- **影响**: 流式回答期间掉帧（用户可感知的"交互卡顿"主因之一）。
- **References**: https://developer.android.com/develop/ui/compose/performance/bestpractices , https://developer.android.com/develop/ui/compose/performance/phases

### C2. 滚底副作用以高频状态为 key，每 chunk 重启动画滚动
- **证据**: `ChatScreen.kt:117-120` — `LaunchedEffect(vm.msgs.size, vm.timeline.size, vm.loading.value, vm.streamText.value, vm.thinking.value)` 内调用 `listState.animateScrollToItem(last - 1)`。`streamText`/`thinking` 每 chunk 变化 → 协程每 chunk 取消重启，动画互相打断（animate 版滚动比 scrollToItem 贵一个量级）。
- **影响**: 流式期间的帧抖动 + 滚动位置跳动。
- **References**: https://developer.android.com/develop/ui/compose/side-effects

### C3. 组合线程上的位图解码 IO（附件缩略图）
- **证据**: `ChatScreen.kt:268` — `remember(item.uri) { loadThumb(context, item.uri) }`：`BitmapFactory.decodeStream`（ContentResolver 文件 IO）跑在组合线程。`remember` 不豁免 IO——首次组合与 key 变化时仍在组合线程执行。
- **影响**: 挂多图附件时首帧卡顿；同类需求仓内已有正确范例（`WallpaperLayer.kt:73-79` 用 `produceState` + `Dispatchers.Default`）。
- **References**: https://developer.android.com/develop/ui/compose/side-effects

## Adjacent Findings

### Android Launch UX
无自定义 splash 主题资源（`res/values*/themes.xml` 无 `windowSplashScreenAnimatedIcon`）→ 本项无发现。

### Window inset / IME 配置（rubric 外，用户原始痛点，单列）
- `AndroidManifest.xml:19` MainActivity 未声明 `android:windowSoftInputMode`（默认 `adjustUnspecified` → 系统倾向 adjustPan 整窗平移），`enableEdgeToEdge()` + `imePadding()`（`ChatScreen.kt:142`）的组合因此失效——键盘弹起时整窗上推、"对话框大幅上移"。
- **修复（一行）**: Manifest 加 `android:windowSoftInputMode="adjustResize"`。FlashActivity 同步受益。
- **References**: https://developer.android.com/develop/ui/compose/immersive-edge-to-edge

### 已知技术债（代码内自证）
- `CanvasCard.kt:8-10` 注释明示：LazyColumn 滚动销毁/重建 WebView 画布卡导致滚动卡顿，MVP 接受、待办缓存快照/单实例复用。

## Category Details

### Performance — 6/10

**编译器实测（Step 4 成功，`app/build/compose_audit/`）**:

```
Performance ceiling check:
  Strong Skipping: ON (Kotlin 2.0.21 默认，featureFlags.StrongSkipping=true)
  module-wide skippable% = 125/239 = 52.3%（207 个 restartable 为 lambda，被 SSM 全量记忆化 265/265）
  named-only skippable% = 32/32 = 100%（app_release-composables.csv，isLambda==0）
  knownUnstableArguments = 42/4661 = 0.9%
  热路径实例重建: 少量（TimelineCard 入参 vm.timeline.toList() 每 chunk 新分配并付 O(n) 结构化 equals；
    Brush.verticalGradient(listOf(...)) 于 MessageItem、panelScrim.copy() 于 FlashActivity）
  → 命中 SSM-on 表第 2 行（≥95% 但少量参数每重组重建/非平凡 equals）→ 天花板 8
  定性得分 6，低于天花板 → 不调整
```

扣分（均为聊天热路径）：
- C1 markdown/切串在组合体内每 chunk 执行（`ChatScreen.kt:209,660`）— bestpractices
- C2 每 chunk 重启 animateScrollToItem（`ChatScreen.kt:117`）— side-effects
- 实例重建滚动：`vm.timeline.toList()`（`ChatScreen.kt:199`）每 chunk 新 List 且作为不稳定参数付全量 equals；建议 hoist 到 `remember(vm.timeline)` 或改传快照 — stability/strongskipping
- WebView 画布卡滚动重建（`CanvasCard.kt:8-10`，代码内已知）— lists
- `FlashActivity.kt:160-170` 拖拽每帧写 `fraction` 状态、经 `fillMaxHeight(fraction)` 非lambda 修饰符逐帧重组；可经 `Modifier.layout {}` 延迟读取 — phases
- `ScreenPrivacyDialog.kt:63` `mutableStateOf(0)` 装箱 — state（轻）

加分：热路径数据类型 `RenderMsg`/`LoopEvent`/`GlassScheme` 编译器判 stable；LazyColumn 全部有稳定 key（`ChatScreen.kt:174`、`HistoryDrawer.kt:156`）；WallpaperLayer 动画走 `graphicsLayer{}` lambda + draw 阶段建 Brush 不逐帧重建（`WallpaperLayer.kt:150-158,174-188`）。

### State Management — 7/10

扣分：
- `collectAsState` 而非 `collectAsStateWithLifecycle`，而依赖 `lifecycle-runtime-compose 2.8.7` **已在 toml 中**（`PlanCard.kt:41`、`FlashActivity.kt:101,107,125`、`WallpaperLayer.kt:65`、`MainActivity.kt:39,46`）——后台仍收集，浪费且违背官方指南 — state
- 字符串路由（`NavRoot.kt`："chat"/"settings"…）在 Navigation Compose 2.8.5 上可用类型安全路由而未用 — navigation（轻，仅 5 屏）
- 抽屉/附件面板等 UI 态用 `remember` 未用 `rememberSaveable`（`ChatScreen.kt:93-95`），旋转即丢 — state（轻）
- ViewModel 持 Compose `mutableStateOf`（`ChatViewModel.kt:56-68`）——官方建议 StateFlow；项目文档化的有意取舍（全局共享大脑），注记不强扣 — architecture

加分：写入统一挂 `app.appScope` + NonCancellable（`SettingsScreen.kt:81-101`，真实 bug 修复留痕）；PlanCard 事件收集 `LaunchedEffect(exec)` keyed 正确（`PlanCard.kt:55`）。

### Side Effects — 7/10

扣分：
- C3 组合线程位图 IO（`ChatScreen.kt:268`）
- C2 的 effect key 问题根因在本类目（以高频态为 key），Performance 已计分不重复扣，此处注记交叉引用
加分：`produceState`+`Dispatchers.Default` 解码壁纸（`WallpaperLayer.kt:73-79`）；`runCatching` 未吞 `CancellationException`（`ScreenTools.kt:177` 显式重抛、`WritePlanExecutor.kt:177` 精确 catch）；无 IO 在组合体（C3 是唯一反例）；GuidePage 常量列表提升到顶层（`GuidePage.kt:8-12`）。

### Composable API Quality — 7/10

扣分（均轻）：
- 全 app 中文硬编码字符串、零 `stringResource`（系统性但为个人中文 app 的有意取舍，注记）
- 复用组件无 `modifier` 参数（HistoryDrawer/GuidePage/CanvasCard）、全仓零 `@Preview`
- 少量裸 `Color(0x…)`（`HistoryDrawer.kt:94`、`ChatScreen.kt:282,502`）绕过 glass 色板
加分：设计系统统一走 `LocalGlassScheme`（compositionLocalOf + 默认值，合规的 tree-scoped 数据）；动画调用带 `label`（`ChatScreen.kt:364,464`、`WallpaperLayer.kt:140`）；事件回调命名规范（onOpenSettings/onNewConversation…）。

## Prioritized Fixes

1. **流式节流 + markdown 记忆化**：`ChatViewModel.kt:498` chunk 合并 80–100ms flush；`ChatScreen.kt:209` 改 `remember(streamText) { renderMarkdownSingle(streamText) }` 或下推到 ViewModel。预期消除流式期间主要掉帧源（C1）。
   - https://developer.android.com/develop/ui/compose/performance/bestpractices
2. **滚底改结构化订阅**：`ChatScreen.kt:117` 改 `LaunchedEffect(listState)` + `snapshotFlow` 观察"新消息且贴底"，`scrollToItem` 非动画版；去掉 streamText/thinking key。预期消除动画打断抖动（C2）。
   - https://developer.android.com/develop/ui/compose/side-effects
3. **缩略图解码移出组合线程**：`ChatScreen.kt:268` 仿 `WallpaperLayer.kt:73` 用 `produceState` + `Dispatchers.IO/Default`（仓内已有范例，照抄即可）。预期附件挂载不再卡首帧（C3）。
   - https://developer.android.com/develop/ui/compose/side-effects
4. **键盘一行修**：Manifest `android:windowSoftInputMode="adjustResize"`（Adjacent 节，用户原始痛点）。
   - https://developer.android.com/develop/ui/compose/immersive-edge-to-edge
5. **collectAsState → collectAsStateWithLifecycle**：6 处替换，依赖已在。
   - https://developer.android.com/develop/ui/compose/lifecycle#collectAsStateWithLifecycle
6. （后置）CanvasCard 快照/单实例化——代码内已列 M2 待办，属重构非修补。

## Notes And Limits

- 编译器诊断：**已使用**（`:app:compileReleaseKotlin` release 变体成功产出 4 份报告）；稳定性结论均引用 `app/build/compose_audit/` 实测行，非源码推断。
- 无 Paging / Nav3 / Animatable / baseline-profile 面，相应 rubric 条目不适用；release 构建未开 R8/minify（个人分发 app，未计分）。
- IME/窗口 inset 发现与鸿蒙端问题不在本 skill 评分范围，单列 Adjacent；鸿蒙端（`setWindowKeyboardAvoidMode`）另见仓库外说明。
- `CanvasHolder` 静态 `@Volatile var` 传画布 HTML 为文档化的有意取舍（对齐鸿蒙 AppStorage），未扣分。

## Suggested Follow-Up

- `compose-agent focus on testing`：全仓零 Compose UI 测试。
- material-3 审计不急：自定义 glass 设计系统为有意架构，M3 合规本就不是目标。
