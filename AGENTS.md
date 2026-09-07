# eli5_harmony（云开）— Agent 规则

## 定位
自用鸿蒙 HarmonyOS NEXT eli5 讲解 app：提问 → 自配大模型以整页 HTML（大字少图、内嵌 SVG）回答，WebView 渲染；必应免 key 联网 + 多轮追问。独立 git 仓库，纯本地（无 remote）。

## 构建与测试
- CLI 构建：`DEVECO_SDK_HOME='D:\Huawei\DevEcoStudio\sdk' node "D:/Huawei/DevEcoStudio/tools/hvigor/bin/hvigorw.js" --mode module -p product=default assembleHap`
- 测试包构建：同命令加 `-p module=entry@ohosTest`；产物分别在 `entry/build/default/outputs/{default,ohosTest}/`
- 单测（模拟器实跑）：`hdc shell aa test -b com.zhuolin.eli5harmony -m entry_test -s unittest OpenHarmonyTestRunner -s class logicTest`（当前 42/42）
- hdc 一律 Windows 反斜杠路径 + `MSYS_NO_PATHCONV=1`；模拟器 127.0.0.1:5555，真机序列号见记忆

## 技术栈与目录
ArkTS（API 26）+ ArkWeb。入口 `entry/src/main/ets/`：
- `pages/` Index（列表）/ Chat（发送管线+WebView）/ Settings
- `service/` LlmClient / SearchClient / SearchRouter / HtmlGuard / HtmlExtractor / PromptBuilder
- `store/` Db（RDB）/ ConversationRepo / MessageRepo / ConfigStore（Preferences）
- 单测 `entry/src/ohosTest/ets/test/Logic.test.ets`（纯逻辑用例）

## 硬约束（违反即出坑）
- ArkWeb 渲染必须 `buffer.from(html).toString('base64')` + `loadData(b64,'text/html','base64')`；data URI 与明文 loadData 都白屏
- attach/init 时序用 webReady+pendingHtml 双标志，别动
- 图标用 entry 级 media（module 级同名 icon 不生效）；改图标须 `bm uninstall` 重装刷桌面缓存
- API key 只存设备本地 Preferences，永不进仓库/报告/记忆
- 9568332 签名不一致 = `bm uninstall` 重装；9568423 UDID 不在 Profile = Project Structure 重勾自动签名
- 必应轨搜索词必须走 `SearchClient.extractKeywords`（整句会被疑问词带偏）

## 当前状态（2026-09-07）
- 模拟器全功能验证通过（22/22 单测 + UI 回归）；真机已装（旧版），待用户首配 key
- 修复史：必应整句污染（extractKeywords）、sanitize 剥 script、防注入、玻璃化、M1 桌面 agent 重构（AgentLoop/SkillStore/双模型分工）
- 当前主模型 glm-5.3-flash（对话/工具调度），use_skill 后长文自动切 longModel（glm-4.7，AppConfig.longModel）
- 待办：真机升级到 agent 版+重配；M2=文件读写+记忆库+SSE 流式+气泡 markdown 渲染；候选迭代：追问建议 / 测验模式 / 语音输入
