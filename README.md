# 云开 Android 版（yunkai-android）

鸿蒙版云开（`../yunkai-harmony`，M1 全量功能）的忠实移植：原生 Android（Kotlin + Jetpack Compose）。

## 它是什么

打开即对话的桌面 agent：

- **AgentLoop 引擎**：LLM function-calling 主循环（10 步上限），内置 `web_search`（必应免 key 爬取 + 博查 + Tavily 三轨）/ `read_web` / `use_skill` 三工具；工具失败以 `{"error":...}` 回传模型自行调整，绝不中断循环；可取消。
- **双模型分工**：主模型对话与调度；`use_skill` 成功后本轮回调切长文模型（仅智谱系端点生效）。
- **技能体系**：内置 eli5 科普讲解配方（首启播种，与鸿蒙版逐字节一致）+ 自建技能 + SKILL.md 粘贴导入；`@技能名` 强制指定，自动路由可关。
- **HTML 画布**：讲解回答渲染成整页 HTML 画布（sanitize 剥 script + WebView data:base64 加载，JS 禁用）。
- **会话管理**：Room 三表持久化，历史抽屉（会话列表长按删除 + 历史轮次定位），杀进程重进恢复。

## 构建

```bash
# 需 ANDROID_HOME 与完整 JDK 21（JBR 不带 jlink 不能用，见 AGENTS.md）
./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM 单测（43 用例语义移植，无需模拟器）
```

minSdk 30（Android 11+），targetSdk/compileSdk 36。首启在设置页填 OpenAI 兼容 API 地址/密钥/模型即可用；搜索默认必应免 key。

## 文档

- `docs/openminis-notes.md`：OpenMinis（GPLv3）只读调研笔记——架构/技能/流式/HTML 渲染四主题思路，M2 backlog 评估
- `../yunkai-harmony/`：移植语义的唯一依据（ArkTS 源码）
- M2 试验田：SSE 流式 → 气泡 markdown → 文件读写 → 记忆库，验证后语义回灌鸿蒙版
