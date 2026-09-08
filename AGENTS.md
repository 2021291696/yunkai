# yunkai-android（云开 Android 版）— Agent 规则

## 定位
自用 Android **桌面 agent**（鸿蒙版云开 M1 的忠实移植，Kotlin + Jetpack Compose）：打开即对话，agent loop 多步工具调用；@技能名 强制技能 / 自动路由可关；回答=聊天气泡或整页 HTML 画布（eli5 为内置 skill）。独立 git 仓库，纯本地（无 remote）。目录 project/yunkai/yunkai-android（双子结构，兄弟仓 yunkai-harmony）。

## 构建与测试
- 环境变量：`ANDROID_HOME=D:\Android\Sdk`，`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`（**实测 JBR 不带 jlink.exe，AGP JdkImageTransform 直接失败**，构建必须用完整 JDK——计划原写 JBR 已按实测修正；路径含空格命令行一律加双引号）
- Maven 源：settings.gradle.kts 优先阿里镜像（dl.google.com 直连/代理均不稳），官方源兜底；gradle wrapper 首跑需代理或已有发行包
- 构建：`./gradlew assembleDebug`，产物 `app/build/outputs/apk/debug/app-debug.apk`
- 单测（JVM，不用模拟器）：`./gradlew testDebugUnitTest`，全量 `./gradlew test`
- 模拟器：`MSYS_NO_PATHCONV=1 /d/Android/Sdk/emulator/emulator.exe -avd quizlens_test &`；`adb install -r <apk>`；`adb shell am start -n com.zhuolin.yunkai/.MainActivity`

## 技术栈与目录
Kotlin 2.0.21 + AGP 8.9.1（compileSdk 36 / minSdk 30 / targetSdk 36）+ Compose BOM 2024.12.01 + Room 2.6.1(KSP) + DataStore 1.1.1 + OkHttp 4.12.0 + kotlinx-serialization 1.7.3。入口 `app/src/main/java/com/zhuolin/yunkai/`：
- `model/Types.kt`（全量类型，@SerialName 蛇形映射）/ `service/`（AgentLoop 引擎 + LlmClient + SearchClient + HtmlGuard/HtmlExtractor + SkillImporter + SearchRouter + tools/）/ `store/`（Room 三表 + 三 Repo + ConfigStore）/ `ui/`（NavRoot + chat/settings/skills/canvas/guide/theme）
- 单测 `app/src/test/java/com/zhuolin/yunkai/`（7 个测试类，移植鸿蒙 Logic.test.ets 43 用例）

## 硬约束（违反即出坑）
- **GPL 红线**：`_reference/OpenMinis*` 可读不可抄，任何文件不得复制进本仓；参考产出物只有 `docs/openminis-notes.md`（纯文字）
- **双模型分工**：主模型 cfg.model（glm-5.3-flash 对话/调度），use_skill 成功后本轮回调切 AppConfig.longModel（glm-4.7 长文）；仅 baseUrl 含 `bigmodel`/`zhipu`（不区分大小写）才允许切；longModel 空串不切换
- LLM 请求体显式 `max_tokens=16384`、`stream=false`（SSE 是 M2 的事）
- 协议字段蛇形：`tool_calls`/`tool_call_id`/`max_tokens` 必须 @SerialName 映射，Kotlin 属性驼峰——缺了 function calling 静默失效
- AgentLoop 工具执行失败一律 `{"error":...}` JSON 字符串回传模型，绝不抛异常中断循环
- API key / 搜索 key 只存设备本地 DataStore，永不进仓库/报告/日志/测试断言
- 渲染形态唯一判定口径：`HtmlGuard.sanitize` + `ReplyKind.detect`（前缀 `<!doctype`/`<html` 不区分大小写）
- WebView 加载 HTML 一律 `data:text/html;base64,...` URL（`loadData` 有中文编码坑，禁止用）；`javaScriptEnabled=false` 双保险
- HTML 解析用正则直译鸿蒙版语义（parseBing/stripTags/decodeEntities），**不引入 jsoup**——保持两端行为逐字节一致
- `SearchClient.STOPWORDS` 与鸿蒙版逐字一致，一个词不许改
- 单文件不超过 500 行；单测全在 `app/src/test/`（JVM），不进 `androidTest/`
- 鸿蒙源码（移植语义唯一依据）：`../yunkai-harmony/entry/src/main/ets/`；**eli5 配方与 yunkai-harmony 的 `entry/src/main/resources/rawfile/skill_eli5.md` 保持逐字节一致（md5 对拍），改动须双端同步**
- 每个 Task 结束即 commit（commit message 不带任何 AI 署名）

## 当前状态（2026-09-09 · run-all 三门全绿）
- A0→A4 全量移植完成并过 run-all 三门：门0 logic-review --full 清零（421d7bc）/ 门1 CLI 72 单测+真 LLM 直连（DEEPSEEK_API_KEY，DeepSeekChainTest 无 key 自动 skip）/ 门2 AI 驱动模拟器九步骤全 PASS（证据 tests/fullflow/reports/2026-09-09_022136_run/）
- M1 功能清单逐项平移：裸对话/AgentLoop 三工具/时间线+取消/双模型分工/@强制/autoRoute/技能管理/eli5 画布/引导页/历史抽屉
- 现场修复两枚（均复验）：技能页返回死键（NavRoot 未传 onBack）；会话切换回归（initialized 守卫拦截 openConversation → 直调 load）
- 已知使用提示：eli5 画布依赖模型产出纯 HTML——deepseek-chat 有前言习惯会按唯一口径降级文本气泡（与鸿蒙一致），glm-4.7/智谱端点下画布稳定
- 真机 K40 七路径手验待用户执行（模拟器无智谱 key，glm 双模型分工真链路未在设备端覆盖）
- M2 试验田顺序：B1 SSE 流式 → B2 气泡 markdown → B3 文件读写 → B4 记忆库；每项 Android 验证稳定后语义回灌鸿蒙
