# yunkai（云开）— Agent 规则

## 定位
自用鸿蒙 HarmonyOS NEXT **桌面 agent**（手机版 zcode）：打开即对话，agent loop 多步工具调用；@技能名 强制技能 / 自动路由可关；回答=聊天气泡或整页 HTML 画布（eli5 为内置 skill）。独立 git 仓库，纯本地（无 remote）。目录 project/yunkai/yunkai-harmony（09-08 迁入双子结构，兄弟仓 yunkai-android）。eli5 配方与 yunkai-android/app/src/main/res/raw/skill_eli5.md 保持一致，改动须双端同步。

## 构建与测试
- CLI 构建：`DEVECO_SDK_HOME='D:\Huawei\DevEcoStudio\sdk' node "D:/Huawei/DevEcoStudio/tools/hvigor/bin/hvigorw.js" --mode module -p product=default assembleHap`；**签名构建另需 `PATH` 前缀 DevEco 的 JBR（`/d/Huawei/DevEcoStudio/jbr/bin`，hap-sign-tool 是 jar，缺 java 报 spawn java ENOENT）且加 `--no-daemon`**（daemon 缓存旧 PATH 与旧 signingConfigs）
- 测试包：同命令加 `-p module=entry@ohosTest`；产物在 `entry/build/default/outputs/{default,ohosTest}/`
- 单测（模拟器实跑）：`hdc shell aa test -b com.zhuolin.yunkai -m entry_test -s unittest OpenHarmonyTestRunner -s class logicTest`（当前 43/43）
- hdc 一律 Windows 反斜杠路径 + `MSYS_NO_PATHCONV=1`；模拟器 127.0.0.1:5555，真机序列号见记忆；Mimosa 拦构建命令时 Write 写 .sh 到 %TEMP% 再 bash

## 技术栈与目录
ArkTS（API 26）+ ArkWeb + RDB。入口 `entry/src/main/ets/`：
- `pages/` Chat（消息流+发送管线，打开即对话的家）/ Index（跳板）/ Settings / SkillManage / Canvas / 组件 CanvasCard·HistoryDrawer·GuidePage
- `service/` AgentLoop（引擎，MAX_STEPS=10+取消+事件回调）/ LlmClient（max_tokens 16384+function calling+chatMessage）/ tools/（AgentTool 载体+web_search·read_web·use_skill）/ SkillImporter / SearchClient（含 extractKeywords）/ HtmlGuard（sanitize+ReplyKind）/ HtmlExtractor / SearchRouter（现役但未接，M2 裁决）
- `store/` Db（conversations/messages/skills 三表）/ ConversationRepo / MessageRepo / SkillRepo / ConfigStore（Preferences，含 longModel/autoRoute）
- 单测 `entry/src/ohosTest/ets/test/Logic.test.ets`

## 硬约束（违反即出坑）
- WebView 加载 HTML 一律 data:base64 URL 作 src（明文 loadData/data URI 会截断）；List 内嵌卡禁用 controller.loadData（实测白屏）
- **双模型分工**：主模型 cfg.model（glm-5.3-flash 对话/调度），use_skill 成功后本轮回调切 AppConfig.longModel（glm-4.7 长文）；glm-5.3-flash 始终深度思考不可关，长页正文会 0 字节
- max_tokens 必须显式 16384（端点默认值会截断长讲解）
- **LlmClient readTimeout 必须 ≥600s**：glm-4.7 非流式长文实测 140s~600s+ 波动（Android 端 180s 两次复现超时后回灌；600s 偶尔也不够，智谱端 500/超时重试即可）
- API key 只存设备本地 Preferences，永不进仓库/报告/记忆
- AgentLoop 工具失败一律 `{"error":...}` 回传模型，绝不抛出中断循环
- 必应轨搜索词必须走 `SearchClient.extractKeywords`；改图标须 bm uninstall 刷缓存；9568332=卸载重装、9568423=重勾自动签名

## 当前状态（2026-09-09）
- M1 桌面 agent 已交付：run-all 三门 PASS（全量审查 1 Important 修 fb3c9dc / logicTest 43/43 / 门2 七路径+双模型回归），报告在 tests/fullflow/reports/
- 09-09 Android 端语义回灌：LlmClient readTimeout 180s→600s + AgentLoop 逐步 hilog（step/模型/耗时/工具输出长度，对标 Android 版 Log.i 'yunkai'），hvigor clean assembleHap 编译通过
- **09-09 真机（Pura 70, 2MH0224513029186）已装签名新版**：新包名 Profile 经 DevEco 自动签名重新签发（签名材料 `~/.ohos/config/default_yunkai-harmony_*`），启动冒烟通过。坑两枚：① products 必须显式 `"signingConfig": "default"` 否则 hvigor 报 No signingConfig found；② 自动签名走 AGC 必须**国内直连**——iKuuuVPN TUN fake-ip 全局拦路时 AGC 403「添加设备失败」，断 VPN 后重试即过
- 模拟器跑的是最新版（glm-5.3-flash 主模型）
- 待办：M2=文件读写+记忆库+SSE 流式+气泡 markdown 渲染+iframe 剥离+List 画布卡重建卡顿
