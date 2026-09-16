# yunkai（云开）— Agent 规则

## 定位
自用鸿蒙 HarmonyOS NEXT **桌面 agent**（手机版 zcode）：打开即对话，agent loop 多步工具调用；@技能名 强制技能 / 自动路由可关；回答=聊天气泡或整页 HTML 画布（eli5 为内置 skill）。独立 git 仓库，纯本地（无 remote）。目录 project/yunkai/yunkai-harmony（09-08 迁入双子结构，兄弟仓 yunkai-android）。eli5 配方与 yunkai-android/app/src/main/res/raw/skill_eli5.md 保持一致，改动须双端同步。

## 构建与测试
- CLI 构建：`DEVECO_SDK_HOME='D:\Huawei\DevEcoStudio\sdk' node "D:/Huawei/DevEcoStudio/tools/hvigor/bin/hvigorw.js" --mode module -p product=default assembleHap`；**签名构建另需 `PATH` 前缀 DevEco 的 JBR（`/d/Huawei/DevEcoStudio/jbr/bin`，hap-sign-tool 是 jar，缺 java 报 spawn java ENOENT）且加 `--no-daemon`**（daemon 缓存旧 PATH 与旧 signingConfigs）
- 测试包：同命令加 `-p module=entry@ohosTest`；产物在 `entry/build/default/outputs/{default,ohosTest}/`
- 单测（模拟器实跑）：`hdc shell aa test -b com.zhuolin.yunkai -m entry_test -s unittest OpenHarmonyTestRunner -s class logicTest`（忆枢后 72/72）

## 全流程测试（run-all）
清单 `tests/fullflow/manifest.yaml` v2（5 条 cli + 8 条 ui）：门1 含 `device_wake_screen`/`device_unlock_screen`——**息屏时 `aa test` 报 10106102**（developer mode 下无法自动解锁），故唤醒+解锁固化成步骤；门2 由 AI 驱动（hdc + uitest）。
- 门1：`cd <repo> && uv run --with pyyaml python <skill>/executor/run.py --manifest tests/fullflow/manifest.yaml --gate api`（platform=cli，门2 无脚本通道）
- 门2：驱动原语见 `tests/fullflow/drive_harmony.sh`（JSON 树遍历定位、唯一文件名截图、逐行 inputText+`keyEvent 66`、收键盘后再滚动等坑写在文件头）
- 报告落 `tests/fullflow/reports/<时间戳>_run/` 与 `<时间戳>_ui/`
- hdc 一律 Windows 反斜杠路径 + `MSYS_NO_PATHCONV=1`；模拟器 127.0.0.1:5555，真机序列号见记忆；Mimosa 拦构建命令时 Write 写 .sh 到 %TEMP% 再 bash

## 技术栈与目录
ArkTS（API 26）+ ArkWeb + RDB。入口 `entry/src/main/ets/`：
- `pages/` Chat（消息流+发送管线，打开即对话的家）/ Index（跳板）/ Settings / SkillManage / Canvas / 组件 CanvasCard·HistoryDrawer·GuidePage
- `service/` AgentLoop（引擎，MAX_STEPS=10+取消+事件回调）/ LlmClient（max_tokens 16384+function calling+chatMessage）/ tools/（AgentTool 载体+web_search·read_web·use_skill）/ SkillImporter / SearchClient（含 extractKeywords）/ HtmlGuard（sanitize+ReplyKind）/ HtmlExtractor / SearchRouter（现役但未接，M2 裁决）
- `store/` Db（conversations/messages/skills 三表）/ ConversationRepo / MessageRepo / SkillRepo / ConfigStore（Preferences，含 longModel/autoRoute/**themeMode/skinId**）
- `common/` WallpaperLayer（背景层：按 skinId 分叉 clear 壁纸/aurora 辉光）/ **ThemeMode**（主题三档 + 皮肤两维经 `applySkinAndTheme` 单点应用；两套枚举不同名，必须显式转换）
- `tests/fullflow/` 门2 清单 + `drive_harmony.sh` 驱动脚本
- `theme/` **Schemes**（皮肤 token 层：SkinScheme + clear/aurora × 明暗四实例，AppStorage('scheme') 整体分发）+ ThemeTokens（几何/动效常量）
- `memory/` 忆枢集成（MemoryStore/注入/摘要/到顶继续，详见 git log 忆枢系列提交）
- 单测 `entry/src/ohosTest/ets/test/Logic.test.ets`

## 硬约束（违反即出坑）
- **皮肤/主题应用只走 `applySkinAndTheme` 单点**；`onConfigurationUpdate` 里**禁止再调 setColorMode**——该回调本身就是 setColorMode 触发的，再调=无限递归栈溢出（jscrash RangeError 真机亲历），回调里只准 `applySkinTokens` 重算 token；ArkTS 常量声明在类后时，类字段默认值/方法体引用它=TDZ ReferenceError（模块初始化 App died 零栈），常量必须上移引用点之前
- WebView 加载 HTML 一律 data:base64 URL 作 src（明文 loadData/data URI 会截断）；List 内嵌卡禁用 controller.loadData（实测白屏）
- **双模型分工**：主模型 cfg.model（glm-5.3-flash 对话/调度），use_skill 成功后本轮回调切 AppConfig.longModel（glm-4.7 长文）；glm-5.3-flash 始终深度思考不可关，长页正文会 0 字节
- max_tokens 必须显式 16384（端点默认值会截断长讲解）
- **LlmClient readTimeout 必须 ≥600s**：glm-4.7 非流式长文实测 140s~600s+ 波动（Android 端 180s 两次复现超时后回灌；600s 偶尔也不够，智谱端 500/超时重试即可）
- API key 只存设备本地 Preferences，永不进仓库/报告/记忆
- AgentLoop 工具失败一律 `{"error":...}` 回传模型，绝不抛出中断循环
- 必应轨搜索词必须走 `SearchClient.extractKeywords`；改图标须 bm uninstall 刷缓存；9568332=卸载重装、9568423=重勾自动签名

## 当前状态（2026-09-13）
- **09-13 双皮肤架构**（`a697b3f`，设计简报 project/yunkai/design-explorations/aurora-brief.md）：设置页「设计」两维（通透=现役 / 极光=辉光替代壁纸）× 明暗三档；颜色全面迁 `theme/Schemes.ets` 运行时 token（$r 仅剩系统能力）；WallpaperLayer/GuidePage/气泡/输入坞按 skin 分叉；升级默认 clear。截图 `tests/fullflow/reports/2026-09-13_aurora_skin/artifacts/`。同日忆枢侧并行收口门0/门2（f26c4bf·2748094·9b68111）
- **09-10 双端 run-all 轮**：清单**重写为 v2**——旧版是 eli5-harmony 时代产物（包名 `com.zhuolin.eli5harmony`、路径写已不存在的「列表页 / 🌐 搜索开关」、单测数 19，且 `health` 段写成 list 让 run.py 直接崩所以从未被跑过），现按云开形态重写：包名 `com.zhuolin.yunkai`、单测 43、门1 五条 cli、门2 八条路径。门1 PASS（`Tests run: 43, Failure: 0`）；门2 7 PASS + 1 挂账：错误 URL 报错路径未触发——保存按钮在折叠下方 + 打字后 IME 挡滚动，收键盘后已能保存；收尾状态已还原，地址与模型均正常）。报告 `tests/fullflow/reports/{20260910-review,2026-09-10_211910_run,2026-09-10_214023_ui}/`
- **门0 3 条 Important 修 1**：`setColorMode` 的 SDK 前置条件（须在窗口已创建且页面 `loadContent` 之后再调；原来只在 loadContent 前调一次，被忽略时会退化成「`$r` 资源浅色 + AppStorage 深色」的半深色）已修——loadContent 前后各应用一次 + 异常补 hilog（`15970d4`）；未修披露：HtmlGuard 只剥 `<script>`（未处理内联 `on*`/iframe/`javascript:`，Web 未关 js）、ThemeMode 零单测
- **09-10 UI 与 Android 版同步**：顶栏 `[‹][标题][⋯]` → `[☰][标题][等宽占位]`（Chat 只经 `replaceUrl` 进入、路由栈内没有上一页，原 ‹ 实为「退出」）；抽屉 ⚙ 无字设置钮、透明遮罩点外部收起、`onBackPress` 收抽屉（此前返回会退出页面）；抽屉标题改「会话」；删「历史轮次」分区；去 📘/⚠️/📋 装饰 emoji；**主题三档**（跟随系统/浅色/深色）
- 09-09 及更早的 M1 交付细节见 git log 与 `tests/fullflow/reports/2026*`

