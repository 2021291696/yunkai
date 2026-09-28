# 云开 Yunkai · 双端手机 AI Agent

<p align="center">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-green">
  <img alt="android" src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white">
  <img alt="harmonyos" src="https://img.shields.io/badge/HarmonyOS%20NEXT-API%2026-000000">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white">
  <img alt="arkts" src="https://img.shields.io/badge/ArkTS-DevEco-0EA5E9">
</p>

打开即对话的本地优先移动端 AI 助手：**agent loop 多步工具调用 + 屏幕感知与写操作三层安全 + 忆枢持久记忆 + 技能系统**，Android（Kotlin / Compose）与 HarmonyOS NEXT（ArkTS）双端同构实现。

> 设计目标：手机上的"数字助理"——能看屏幕、能动手操作、能记住你的偏好，
> 但每一步写操作都要经你逐条批准。

**Yunkai** is a local-first mobile AI agent: an agent loop with multi-step tool calling,
accessibility-based screen perception gated by a three-layer safety model, a persistent
memory engine, and a skill system — implemented twice, in Kotlin/Compose (Android) and
ArkTS (HarmonyOS NEXT). Model access is OpenAI-compatible: bring your own endpoint and
key, stored on-device only.

---

## 它能做什么

- **点开悬浮球，半屏面板直接对话**——不用切出当前应用；面板可拖拽在半屏 / 全屏（70% 阈值）间切换，与主界面共享同一个大脑（会话、记忆、任务状态），底色三档可调。
- **"帮我把这条消息发给张三"**——模型不能直接操作手机：它只能通过计划卡提交动作计划（点击 / 滑动 / 输入 / 返回 / Home），你逐条批准后状态机才逐步执行并回显。
- **记住你的偏好**——内置「忆枢」记忆引擎：摘要、BM25 检索、隐私挡位三级可控，strict 挡下说出口的敏感信息不入库。
- **技能系统**——`@技能` 显式调用或自动路由；内置 eli5 讲解（画布渲染）与狗头军师两个技能，也可导入自己的技能说明书。
- **文档附件问答**——图片 / txt / docx / xlsx / PDF，双端自研 zip+XML 抽取，不依赖第三方解析服务。

## 功能总览

| 能力 | Android | HarmonyOS NEXT |
|------|:---:|:---:|
| Agent loop（多步工具调用 / 取消 / SSE 事件流） | ✅ | ✅ |
| 屏幕感知：无障碍读屏 → 结构化控件清单 | ✅ | ✅ |
| 写操作：计划卡预览 → 逐条批准 → 手势执行 | ✅ | ✅ |
| 三层安全：敏感页护栏 / 每步回显 / 外发二次确认 | ✅ | ✅ |
| 隐私黑名单（银行 / 支付类默认拒绝读屏） | ✅ | ✅ |
| 悬浮球 + 半屏 / 全屏对话面板（底色三档） | ✅ | ✅ |
| QS 磁贴快捷入口 | ✅ | ✅ |
| 忆枢记忆引擎（摘要 / BM25 检索 / 隐私挡位） | ✅ | ✅ |
| 技能系统（@技能 / 自动路由 / eli5 画布，双内置） | ✅ | ✅ |
| 文档附件问答（图片 / txt / docx / xlsx / PDF） | ✅ | ✅ |
| 主题三档 + 双皮肤 | ✅ | ✅ |

## 架构

```mermaid
flowchart LR
    UI["UI 层\n主界面 / 悬浮球面板 / 计划卡 / 设置"] --> VM["ChatViewModel\n共享大脑"]
    VM --> Loop["AgentLoop\n多步工具调用 / 取消 / 事件流"]
    Loop --> Tools
    subgraph Tools [工具层]
        Builtin["内置工具\n搜索 / 网页 / 附件抽取"]
        Screen["屏幕感知\n读屏 / 截图 / 应用列表"]
        Write["写操作状态机\n计划卡 → 逐条批准 → 执行"]
        Memory["忆枢记忆\n摘要 / 检索 / 归档"]
        Skill["技能系统\n@技能 / 自动路由 / 画布"]
    end
    Loop --> LLM["LlmClient\nOpenAI 兼容协议 / SSE 流式"]
    Tools --> Store[("本地存储\nRoom (Android)\nRDB + Preferences (HarmonyOS)")]
    Memory --> Store
```

- **Android**：Kotlin 2.0 / Compose / Room / DataStore / AccessibilityService（`takeScreenshot` 需 API 30+，故 minSdk 30）
- **HarmonyOS**：ArkTS / API 26 / RDB / Preferences / 无障碍扩展（独立进程 `:accessibility`）

## 安全模型：只看不动，除非逐条批准

屏幕感知是本项目的核心差异点，安全设计围绕"模型不直接碰手机"展开：

1. **计划卡**：写操作只能经 `propose_plan` 提交，聊天界面内嵌计划卡逐步展示，用户点「执行」后状态机推进并逐步回显；
2. **敏感护栏**：节点树文本命中敏感关键词（支付 / 转账 / 验证码等）即进入暂停态，写操作止步；
3. **外发确认**：含文本输入的动作单独走二段确认位；
4. **黑名单默认不信**：银行 / 支付类应用直接拒绝读屏，用户可增补；读屏内容按隐私挡位脱敏，strict 挡下转录不入库；
5. **注入防御**：工具返回的一切外部内容（网页 / 搜索 / 技能 / 附件）统一加"不可信数据"前缀并写入系统守则——其中出现的指令性文字不构成指令，阻断间接提示注入 → 记忆投毒链；
6. **取消即断连**：生成中途取消会真正中断 SSE 流，在途响应不再计费；
7. **附件防线**：docx / xlsx 解压设 5MB 上限、组装截断 30k 字符，防 zip 炸弹与上下文撑爆。

## 截图

| 悬浮球 + 闪问面板 | 写操作计划卡 |
|---|---|
| ![闪问面板](docs/screenshots/android_flash_answer.png) | ![计划卡](docs/screenshots/android_plan_card.png) |

| 鸿蒙端（深色） | 屏蔽应用管理 |
|---|---|
| ![鸿蒙端](docs/screenshots/harmony_dark.png) | ![黑名单](docs/screenshots/android_blacklist.png) |

## 构建与运行

两端的模型接入均为 **OpenAI 兼容协议**：自备 base URL 与 API key，仅存设备本地（Preferences / DataStore），不经过任何中间服务器。

### Android

```bash
cd yunkai-android
# Android Studio 打开，或命令行：
./gradlew assembleDebug          # 产物 app/build/outputs/apk/
./gradlew testDebugUnitTest      # JVM 单测（200+ 用例）
```

minSdk 30（无障碍截图 `takeScreenshot` 需 API 30+）。已在 MIUI 真机完成验证。

### HarmonyOS NEXT

```bash
cd yunkai-harmony
# DevEco Studio 打开（API 26），或命令行：
hvigorw --mode module -p product=default assembleHap
# 设备单测（模拟器/真机在线时）：
hdc shell aa test -b com.zhuolin.yunkai -m entry_test -s unittest OpenHarmonyTestRunner -s class logicTest
```

首次使用：设置页填入端点与 API key → 开启屏幕感知总开关 → 系统设置里授权无障碍服务。

## 测试体系

| 层 | 内容 |
|---|---|
| 代码审查 | 提交前按 checklist 逐项走查 |
| 单元测试 | Android JVM **200+** 用例 / HarmonyOS **81** 用例 |
| 端到端验收 | 模拟器上 AI 驱动的全链路路径：`tests/fullflow/manifest.yaml` 声明式定义，ADB / HDC + UI 自动化执行，截图留证 |

双端共享同一组忆枢测试向量（BM25 / 迁移 / 隐私闸门），逐字节一致。

## 目录结构

```
yunkai/
├── yunkai-android/     # Android 端（Kotlin 2.0 / Compose / Room / DataStore / AccessibilityService）
│   └── app/src/main/java/com/zhuolin/yunkai/
│       ├── service/        # AgentLoop / LlmClient / screen/(感知服务+工具+写操作状态机) / memory/
│       ├── store/          # Room + DataStore（会话 / 配置）
│       └── ui/             # Chat / Settings / 悬浮球面板 / 计划卡 / 记忆管理
├── yunkai-harmony/     # HarmonyOS 端（ArkTS API 26 / RDB / Preferences）
│   └── entry/src/main/ets/
│       ├── service/        # AgentLoop / LlmClient / tools/ / screen/(无障碍扩展+纯逻辑层)
│       ├── store/          # RDB + Preferences
│       ├── memory/         # 忆枢记忆引擎
│       └── pages/          # Chat / Settings / Canvas
└── docs/               # 截图
```

## License

[MIT](LICENSE)
