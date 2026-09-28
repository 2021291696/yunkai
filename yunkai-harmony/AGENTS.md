# yunkai（云开）— Agent 规则

## 定位
自用鸿蒙 HarmonyOS NEXT **桌面 agent**（手机版 zcode）：打开即对话，agent loop 多步工具调用；@技能名 强制技能 / 自动路由可关；回答=聊天气泡或整页 HTML 画布（eli5 为内置 skill）。独立 git 仓库，纯本地（无 remote）。目录 project/yunkai/yunkai-harmony（09-08 迁入双子结构，兄弟仓 yunkai-android）。eli5 配方与 yunkai-android/app/src/main/res/raw/skill_eli5.md 保持一致，改动须双端同步。

## 构建与测试
- CLI 构建：`DEVECO_SDK_HOME='D:\Huawei\DevEcoStudio\sdk' node "D:/Huawei/DevEcoStudio/tools/hvigor/bin/hvigorw.js" --mode module -p product=default assembleHap`；**签名构建另需 `PATH` 前缀 DevEco 的 JBR（`/d/Huawei/DevEcoStudio/jbr/bin`，hap-sign-tool 是 jar，缺 java 报 spawn java ENOENT）且加 `--no-daemon`**（daemon 缓存旧 PATH 与旧 signingConfigs）
- 测试包：同命令加 `-p module=entry@ohosTest`；产物在 `entry/build/default/outputs/{default,ohosTest}/`
- 单测（模拟器实跑）：`hdc shell aa test -b com.zhuolin.yunkai -m entry_test -s unittest OpenHarmonyTestRunner -s class logicTest`（09-18 安全收口轮后 84/84；用例数随迭代增长，以实跑为准）

## 全流程测试（run-all）
清单 `tests/fullflow/manifest.yaml` v2（5 条 cli + 8 条 ui）：门1 含 `device_wake_screen`/`device_unlock_screen`——**息屏时 `aa test` 报 10106102**（developer mode 下无法自动解锁），故唤醒+解锁固化成步骤；门2 由 AI 驱动（hdc + uitest）。
- 门1：`cd <repo> && uv run --with pyyaml python <skill>/executor/run.py --manifest tests/fullflow/manifest.yaml --gate api`（platform=cli，门2 无脚本通道）。**构建须 default 与 ohosTest 两跑**：只跑 `-p module=entry@ohosTest` 不更新 default hap——测试代码新、被测主代码旧，报「GetRawFileContent failed」类错位错误（09-17 实坑；主代码/rawfile 改动必须重跑 default 构建再装）
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

## 当前状态（2026-09-19）
- **09-19 深夜 撤销 M3 Task5/6 闪问（revert `3c8fd7b`+`6792b99` → `f361d13`）**：闪问页+服务卡片曾按 M3 计划做完并真机验收（真 LLM 全链路/卡片直达/FormMgr RENDERED/门1 PASS），交付后用户判定**与开发初衷相悖**——闪问的存在前提是 Android 悬浮球「从任意 app 随时唤起」，鸿蒙悬浮球不可行后桌面卡片入口与直接打开 app 等价，**场景前提已塌**；独立无上下文问答页也与「完整桌面 agent、打开即对话」定位不符。相关代码全撤（Flash.ets/FlashRepo/flash_sessions 表/EntryFormAbility/卡片/抽屉分区/manifest ㉑ 路径）。**保留**：manifest 门1 三修复（计数断言 84、五步 `-t 127.0.0.1:5555` 双设备适配、移除本仓不存在的 hvigor clean 任务）与真机裁决轮全部成果（锁屏三修、key 修复、真机门1 84/84）。**教训：计划 Task ≠ 仍值得做——前提变化后应先质疑价值再执行**；Android 侧闪问（悬浮球场景成立）暂不动，但若产品定位收敛需重审
- **09-19 真机裁决轮（两项挂账定案，2MH0224513029186）**：①**M3 真机三步验证=被系统阻塞**——华为消费版（HarmonyOS NEXT 商用）与模拟器同样**不开放三方无障碍启用入口**：关怀和无障碍页无服务组、快捷键选择功能仅 5 个内置项、设置搜索"无障碍服务"零结果、AccessibilityManagerService client=0。三方无障碍须应用市场上架审核后才出现在设置（分发管控，非自动化可绕）→ **Task2-4（读屏/写操作）端到端永久挂真机解锁，Task5-7（闪问页/服务卡片/三门）不依赖无障碍可先行**。②**「真机门2 锁屏掉线」系误诊**——真机抓包（LlmClient 诊断锚点 `yunkai_stream_first/done`）实锤：**智谱 key 已过期**，端点对过期令牌回 **HTTP 200 + JSON 错误体**，SSE 解析无 data 行→空 content→被当成功回答落库（09-14 conv1 空回答同因；锁屏从未打断过流，落库时间戳同秒为证）。**三修已落（本日 commit）**：a) chatStream 识别 200+错误体→按 `LLM <code>: <msg>` reject；b) send() 空回答兜底 throw；c) EntryAbility.onForeground 发 `ykResumeAt` tick→Chat 生成中轮次 fail-fast（作废 gen+还原输入+可重试，防真锁屏场景 UI 永挂中间态）。验证：错误路径真机实测（toast+「已停止（过程保留）」+输入还原+DB 零落库，修复前 19:50/19:51 两轮各落一空行对照）；**真机门1 logicTest 84/84**（首次真机跑通）。**待用户**：设置页更新有效 API key 后才能真机真 LLM 链路验证；真机上 4 条测试残留会话（3 条空回答）待用户手删或拍板
- **09-18 安全审查收口轮（M-1 转录侧信道）**：全仓安全审计后按 §5.3 新协议把隐私闸门前置到**转录入库**——`Chat.ets` 两条落库点（user/assistant，359/480 区）经 `PrivacyGate.redact(content, await ConfigStore.getMemoryGear(ctx))` 脱敏，strict 挡下说出口的密码不再留存可检索转录；`PrivacyGate.ets` 增 `redact()`（新鲜 RegExp 规避 /g 状态）。**Low-2 同轮收口**：`MemoryTools.ets` 5 处 catch 文案改固定短文案（不再回传内部异常消息进模型上下文）、拒存文案挡位归一两处对齐。验证：default+ohosTest 双构建绿 + aa test logicTest **84/84**（含 3 个 redact 用例，模拟器实测）；yishu 评测真跑 transcript_not_contains 4/4（总 178/208，deepseek 逐轮波动非回归）。镜像 4 文件逐字节一致
- **09-18 忆枢说明块 v2（yishu 缺口收口轮回灌）**：`memory/MemoryInjection.ets` 的 `MEMORY_GUIDE` 按 yishu 协议 §4.2 v2 重写——内容分流三规则（用户稳定信息默认核心块 / 一次性事件与他人单次提及默认归档 / 「归档·记个档案·记个念头」指令动词覆盖改走归档、「写进核心档案」改走核心）+「信息变更用 replace 换旧值，不要新旧并存」。PC 评测 DeepSeek 真跑 **74.5%→86.8%**（177/204，报告在 yishu 仓 `harness/reports/real_eval_v2guide_20260918.log`）。default+ohosTest 双构建绿 + aa test logicTest **81/81**（模拟器实测，22:47）；yishu 侧协议 md5 清单 4/4、selftest 40/40、三镜像文件逐字节一致
- **09-17 屏幕感知 M3 Phase0 spike + T1 逻辑层（`5c94eeb`→`6a8e9ba`）**：五问调研与实施计划在 `../design-explorations/2026-09-16-harmony-accessibility-research.md`（截图=不可行/手势+读屏=可平移/悬浮球+磁贴=服务卡片替代）+ `2026-09-16-m3-harmony-sync-plan.md`。**Phase0 已钉死**：accessibility_config.json **8 键缺一不可**（源码级考证自 openharmony/accessibility `aams/src/utils.cpp`；abilityTypes 合法值 spoken/visual/generic 等，"talkBack" 非法）；扩展跑**独立进程** `:accessibility`→P1 通信定案 commonEvent 桥。**硬阻塞**：模拟器设置无三方无障碍启用入口（回调存活/根节点读取挂真机验证，解锁前 P1 读屏接线暂缓）。T1 纯逻辑层已落 `service/screen/ScreenLogic.ets`（schema 双端同源）门1 80/80。骨架三件套：`ScreenSenseExtension.ets`+`module.json5` accessibility 注册+`accessibility_config.json`
- **09-17 狗头军师内置技能（`23d5de7`）**：`rawfile/skill_goutoujunshi.md` 与 Android 端 md5 一致（单文件自包含适配版）；`seedIfEmpty`（表空才插，老用户收不到新技能）重构为 `seedBuiltins` 按名幂等播种双内置；中文 frontmatter 解析与双技能播种用例，门1 81/81。拉头改版（双横线+停抽屉外+抽屉 80%）同日交付（`be59171`），HistoryDrawer/Chat 两处宽度与位移须一致改
- **09-13 双皮肤架构**（`a697b3f`，设计简报 project/yunkai/design-explorations/aurora-brief.md）：设置页「设计」两维（通透=现役 / 极光=辉光替代壁纸）× 明暗三档；颜色全面迁 `theme/Schemes.ets` 运行时 token（$r 仅剩系统能力）；WallpaperLayer/GuidePage/气泡/输入坞按 skin 分叉；升级默认 clear。截图 `tests/fullflow/reports/2026-09-13_aurora_skin/artifacts/`。同日忆枢侧并行收口门0/门2（f26c4bf·2748094·9b68111）
- **09-11 run-all 全收（补记）**：门0 三条 Important 全收——HtmlGuard 安全面加固（内联 `on*`/`javascript:` 伪协议/`<iframe>` 整剥 + 回归用例）+ Canvas/CanvasCard `javaScriptAccess(false)` 双保险 + ThemeMode 单测（`ee7b01a`）；Android 同日 `2a9ade1`。下方 09-10 条目「未修披露：HtmlGuard 只剥 script、ThemeMode 零单测」自此失效
- **09-10 双端 run-all 轮**：清单**重写为 v2**——旧版是 eli5-harmony 时代产物（包名 `com.zhuolin.eli5harmony`、路径写已不存在的「列表页 / 🌐 搜索开关」、单测数 19，且 `health` 段写成 list 让 run.py 直接崩所以从未被跑过），现按云开形态重写：包名 `com.zhuolin.yunkai`、单测 43、门1 五条 cli、门2 八条路径。门1 PASS（`Tests run: 43, Failure: 0`）；门2 7 PASS + 1 挂账：错误 URL 报错路径未触发——保存按钮在折叠下方 + 打字后 IME 挡滚动，收键盘后已能保存；收尾状态已还原，地址与模型均正常）。报告 `tests/fullflow/reports/{20260910-review,2026-09-10_211910_run,2026-09-10_214023_ui}/`
- **门0 3 条 Important 修 1**：`setColorMode` 的 SDK 前置条件（须在窗口已创建且页面 `loadContent` 之后再调；原来只在 loadContent 前调一次，被忽略时会退化成「`$r` 资源浅色 + AppStorage 深色」的半深色）已修——loadContent 前后各应用一次 + 异常补 hilog（`15970d4`）；未修披露：HtmlGuard 只剥 `<script>`（未处理内联 `on*`/iframe/`javascript:`，Web 未关 js）、ThemeMode 零单测
- **09-10 UI 与 Android 版同步**：顶栏 `[‹][标题][⋯]` → `[☰][标题][等宽占位]`（Chat 只经 `replaceUrl` 进入、路由栈内没有上一页，原 ‹ 实为「退出」）；抽屉 ⚙ 无字设置钮、透明遮罩点外部收起、`onBackPress` 收抽屉（此前返回会退出页面）；抽屉标题改「会话」；删「历史轮次」分区；去 📘/⚠️/📋 装饰 emoji；**主题三档**（跟随系统/浅色/深色）
- 09-09 及更早的 M1 交付细节见 git log 与 `tests/fullflow/reports/2026*`

