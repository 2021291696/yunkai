# yunkai-android（云开 Android 版）— Agent 规则

## 定位
自用 Android **桌面 agent**（鸿蒙版云开 M1 的忠实移植，Kotlin + Jetpack Compose）：打开即对话，agent loop 多步工具调用；@技能名 强制技能 / 自动路由可关；回答=聊天气泡或整页 HTML 画布（eli5 为内置 skill）。独立 git 仓库，纯本地（无 remote）。目录 project/yunkai/yunkai-android（双子结构，兄弟仓 yunkai-harmony）。

## 构建与测试
- 环境变量：`ANDROID_HOME=D:\Android\Sdk`，`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`（**实测 JBR 不带 jlink.exe，AGP JdkImageTransform 直接失败**，构建必须用完整 JDK——计划原写 JBR 已按实测修正；路径含空格命令行一律加双引号）
- Maven 源：settings.gradle.kts 优先阿里镜像（dl.google.com 直连/代理均不稳），官方源兜底；gradle wrapper 首跑需代理或已有发行包
- 构建：`./gradlew assembleDebug`，产物 `app/build/outputs/apk/debug/app-debug.apk`
- 单测（JVM，不用模拟器）：`./gradlew testDebugUnitTest`，全量 `./gradlew test`
- 模拟器：`MSYS_NO_PATHCONV=1 /d/Android/Sdk/emulator/emulator.exe -avd quizlens_test &`；`adb install -r <apk>`；`adb shell am start -n com.zhuolin.yunkai/.MainActivity`

## 模拟器 UI 自动化坑（趟平，勿重踩）
- **AVD quizlens_test 每次冷启动丢 userdata**（装的应用/配置全没）——重启电脑或模拟器后必须重装 app + ADBKeyboard + 重填智谱配置。验证配置真保存看 `adb shell "run-as com.zhuolin.yunkai ls files/datastore/"` 有 `yunkai_cfg.preferences_pb` 才算数（设置页里显示的 baseUrl 是 placeholder 不是已填值）
- **切 IME 只有 `adb shell ime set com.android.adbkeyboard/.AdbIME` 生效**；`settings put secure default_input_method` 不生效。中文输入：`am broadcast -a ADB_INPUT_TEXT --es msg '中文'`；**`ADB_CLEAR_TEXT` 经常不生效**，清空用 `input keycombination 113 29`（Ctrl+A）+ `input keyevent 67`（Del）
- **"ADB Keyboard {ON}" 底栏常驻会挡住屏幕底部按钮**（保存键被挡过一次导致保存没生效）——点击前把目标滚到 y<550 区域
- 坐标随时漂移：每次操作前 `uiautomator dump` 取 bounds 算中心点再 tap，tap 后验证 focused 落在目标节点；BACK 偶尔需按两次，按多了会退出到别的 app，用 `am start -n com.zhuolin.yunkai/.MainActivity` 拉回
- **AVD 被孤儿 qemu 占锁**：启模拟器报 `Running multiple emulators with the same AVD` 时，只杀 launcher 不够——上一轮的 `qemu-system-x86_64` 子进程还活着占着锁（`rm multiinstance.lock` 报 `Device or resource busy`）。正解 `taskkill /F /T /PID <launcher>` 连子树杀 + 删 `~/.android/avd/quizlens_test.avd/multiinstance.lock` 与 `hardware-qemu.ini.lock/`；**别按进程名模糊匹配杀**（会拿到 DevEco 的 `Emulator.exe`，把鸿蒙模拟器杀掉）
- **含空格的广播文本要给设备侧 shell 加引号**：`adb shell` 会把参数里的空格再切一次词——`adb shell am broadcast --es msg 'name: 中文'` 只传过去 `name:`；正确写法 `adb shell "am broadcast -a ADB_INPUT_TEXT --es msg 'name: 中文'"`。多行文本=逐行 broadcast + `input keyevent 66`
- **dump 取不到的两类东西**：`uiautomator dump` 会漏长文本节点（assistant 长回答整段不出现）、Toast 也不在树里 → 内容级断言看截图、机制证据看 `logcat -s yunkai`（AgentLoop 逐步日志）

## 全流程测试（run-all）
清单 `tests/fullflow/manifest.yaml`（单路径 base：3 条 cli + 12 条 ui）；门2 驱动原语见 `tests/fullflow/drive_android.sh`（坐标全部由 dump 动态解析，平台坑写在文件头）。
- 门1：`export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot" ANDROID_HOME=D:\Android\Sdk` 之后 `uv run --with pyyaml python <skill>/executor/run.py --manifest tests/fullflow/manifest.yaml --gate api`——**run.py 与 MCP 都不设环境变量**，必须在父 shell export，否则子进程继承到 IntelliJ 的 JBR（缺 jlink）直接 RED
- 门2：AI 驱动模拟器；`android-emulator` 的 `android_build_and_run` 不设 JAVA_HOME（同样撞 JBR）→ **用 Temurin 预构建 APK，只用 MCP 起模拟器/装/启**
- 报告落 `tests/fullflow/reports/<时间戳>_run/`（门1）与 `<时间戳>_ui/`（门2，含 `agent_results.json` + `artifacts/agent-ui/<runID>/` 截图）

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
- **LlmClient readTimeout 必须 ≥600s**：非流式 glm-4.7 长文生成实测 140s+，180s 必超时（模拟器真 key 两次复现 `send failed: timeout`）
- 鸿蒙源码（移植语义唯一依据）：`../yunkai-harmony/entry/src/main/ets/`；**eli5 配方与 yunkai-harmony 的 `entry/src/main/resources/rawfile/skill_eli5.md` 保持逐字节一致（md5 对拍），改动须双端同步**
- 每个 Task 结束即 commit（commit message 不带任何 AI 署名）
- **改 `app/src/**` 会被 video2code 插件的 `check_plan_first.py` 必拦**（它按路径判：`<project>/app/src/**` 当网站组件源码，要求 `out/plan.md` 存在）→ 正解是用 Bash + python 做精确字符串替换落盘；**别为解封去创建 `out/plan.md`**（hook 源码写明写它即认领契约，会触发 Stop hook 强制收尾）

## 当前状态（2026-09-10）
- **09-10 双端 run-all 轮，本仓三门全绿**：门0 全量审查（整库 31 文件 3314 行）抓到 **1 blocking**——`ChatViewModel.load()` 换会话不作废在途 `send`（旧轮回来会把回答写进新会话 + `nextId` 归 1 撞 id 触发 LazyColumn 重复 key 崩；鸿蒙版靠 replaceUrl 换新页实例天然规避，属移植回归），已修 `6d7ec97`（`genId += 1` + `loading = false`），并把该场景补成清单 ⑮ 回归路径；门1 PASS（单测 73 / assembleDebug / 真连 DeepSeek `LLM_CHAIN_OK`）；门2 **12/12 PASS**（裸对话、搜索链路 web_search×2+read_web×2、取消中断、@eli5 全屏画布 11 节目录、抽屉两条收起、在途切会话）。报告 `tests/fullflow/reports/{20260910-review,2026-09-10_211653_run,2026-09-10_212002_ui}/`
- **09-10 UI 收口（用户按截图报的 5 条 + 同类审计）**：顶栏开钮挪左上 `☰`（右侧等宽占位保持标题居中）；「收起 ✕」「设置」文字钮 → ✕/⚙ 圆玻璃符号钮；抽屉点面板外 + 系统返回都能收（此前按返回会退出 app）；引导页去掉 🌤️；**补 `android:icon`**（此前从未声明，桌面图标一直是系统默认机器人占位图）；抽屉标题「会话与历史」→「会话」、长按提示上标题行；删掉下半「历史轮次」分区
- **主题三档**（跟随系统/浅色/深色）：`store/ConfigStore.themeModeFlow` + `MainActivity` 订阅决定 `darkTheme`，手动档即时生效并持久化，系统栏图标随主题翻转
- 09-09 移植交付（A0→A4 全量平移 + 三门验收）细节见 git log 与 `tests/fullflow/reports/2026-09-09*`
- 已知使用提示：eli5 画布依赖模型产出纯 HTML——deepseek-chat 有前言习惯会按唯一口径降级文本气泡，glm-4.7/智谱端点下画布稳定；智谱端长文耗时 140s~600s+ 波动（服务端现象，重试即过）
- **模拟器 app 数据 09-10 被清过一次**（验图标需卸载重装、取引导页需 `pm clear`）：再跑真 LLM 路径要先在设置页重填一次 API 配置

