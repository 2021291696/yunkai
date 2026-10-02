# yunkai-android run-all 挂账台账（唯一权威载体；manifest 头部 `# pending:` 与交付摘要挂账清单均从本表派生）

格式：每行 = 路径/事项 ID + 卡点 + 收口判据 + 来源轮。下轮 run-all 预检前先对台账逐条收口或确认仍挂。

| ID | 事项 | 卡点 | 收口判据 | 来源轮 | 状态 |
|----|------|------|---------|--------|------|
| P1 | 悬浮球拖动换位与面板拖拽手感目检 | `input swipe` 只能验几何，手感/阻尼/贴边观感需人手 | 真机人手操作无违和；两皮肤（通透/极光）各目检一遍 | 09-25 emulator round1 + AGENTS.md 09-19 | 挂（本轮真机抽验做几何面，手感仍待人手） |
| P2 | 服务断开 toast 去抖 5s 目检 | toast 不入 UI 树，闪断节奏需人眼；模拟器不复现 MIUI 高频闪断 | 真机闪断场景下 5s 内不弹「已断开」、超 5s 未重绑才弹一次 | 09-25 emulator round1（真机目检记录未存档不可追溯→待复测） | 挂（待人眼） |
| P3 | 微信本体链路（真机自绘 app 读屏：微信/抖音） | 模拟器无微信；真机轮记录不可追溯 | 真机微信前台→面板态读屏走视觉路线且回复描述微信真实界面 | 09-25 emulator round1 | 挂（本轮真机在线则抽验收口） |
| P4 | 真机黑名单 UI 路径（设置页屏蔽应用管理入口配置黑名单后负向拦截） | 09-25 黑名单由 craft 脚本直写 datastore，未走 UI 入口 | 真机设置页 UI 添加黑名单 app→负向拒绝 | 09-25 emulator round1 | 挂（本轮真机在线则抽验收口） |
| P5 | 单文件 500 行线：ChatScreen 704 / ChatViewModel 612 | 历史存量，拆分另立任务（09-14 门0 挂账） | 拆分提交 | 09-14 runall summary | 挂（非悬浮窗本轮范围，不阻塞） |
| P6 | use_skill 软预算占位文案仍切长文（双端同构瑕疵，minor） | 门0 minor，修复另立 | 修复+回归 | 09-14 runall summary | 挂（非悬浮窗本轮范围，不阻塞） |
| P7 | harmony 门2（真机锁屏掉线） | 鸿蒙侧独立轮 | 鸿蒙 run-all 轮 | 09-14 runall summary | 挂（鸿蒙仓，本轮安卓范围外） |
| P8 | 开源镜像同步口径（09-19 悬浮球重构是否同步 GitHub） | 用户拍板事项 | 用户拍板后同步 | AGENTS.md 09-19 | 挂（决策项，非测试） |
| P9 | 悬浮球「拖动贴边」承诺未实现（含 FLAG_LAYOUT_NO_LIMITS 无边界 clamp 可拖出屏外） | **拍板（2026-10-01）：贴边+手感一起做**——已落地：松手吸最近左右缘（180ms 减速动画）+全屏 clamp+按住提亮；模拟器实测 (20,400)→拖→吸 (0,279)/(276,319) | ✅ 已实现；手感目检挂 P13 真机轮 | 20261001-fw-round1-review W-A6 + 用户拍板 | 已收口（实现面）；目检挂 P13 |
| P10 | Room schema 未导出 + 迁移无自动化测试（B3 崩溃缺陷即此盲区产物） | 门0 M-C9：无 room.schemaLocation、无 MigrationTestHelper 用例 | 开 ksp room.schemaLocation + 历史版本 JSON 入仓 + 迁移 Robolectric 用例 | 20261001-fw-round1-review | 挂（加固项） |
| P11 | 门0 Warning 清单（A2 黑名单污染残余面/A3 visionLearned 误记/A4 PlanTool BAL+A11y/A5 captureScreen 取消语义；B1 抽屉关动画卡死/B2 send 落库窄窗/B3 壁纸与黑名单写入漏挂 appScope/B4 SettingsScreen 520 行超限；C2 取消不断网读/C3 主线程 IO/C4 BM25 主线程/C5 OkHttp 重建/C6 吞 CancellationException/C7 arguments 双形态） | 门0 披露项，本轮不修 | 逐项修复+回归 | 20261001-fw-round1-review/review.md | 挂（修复积压） |
| P12 | 面板态读屏正向失效：真机根因已实锤——HyperOS 类名启发式盲区（桌面 Launcher/设置 MiuiSettings 不含 "Activity"，fgPkg 过滤器整类误杀） | 根因实锤（诊断日志 rd11）→ **修复已落地并端到端闭环（2026-10-02 凌晨）**：ScreenLogic.isActivityWindow 允许名单（Activity/Settings/.Launcher）替换 contains("Activity")，ScreenLogicTest +4 用例；真机实测 `fgPkg 更新: com.android.settings (cls=MiuiSettings)` 入账 → 面板浮 MIUI 设置上问屏 → **正向转述**（WLAN/蓝牙/状态栏电量逐项+自身悬浮窗自察，rd15_p12_e2e.png；修复前同场景 fail-closed 拒绝） | ✅ 完全收口 | 20261001_realdevice_round2 发现① + rd15 | 已收口 |
| P13 | 真机抽验整套（df07a4cd Redmi/Android 16）| —— | **2026-10-01 真机轮已跑**：装机✓/Y1 闪断补显✓（含 30s×2 自发闪断实拍）/P9 贴边双缘✓/B2 密度判别✓（300px 落回 0.6 不跳全屏）/P4 黑名单 UI+负向拒绝✓/Y5 toast 去抖录屏胶片✓；**剩**：P3 微信读屏（隐私出网需用户主动发起）、拖拽手感与指示圈人眼把玩、鸿蒙 P14 回归 | 20261001_realdevice_round2/REPORT.md | 大部分收口；余项见上 |
| P14 | @技能强制注入语义：「@技能名 强制技能」实现为模型自决调 use_skill，长历史纯文本会话中被抑制（⑧ 连续 3 次降级+㉗ 抢答轮同族）；画布连带不出现 | **拍板（2026-10-01）：@-mention 直接注入（双端同构）**——已落地：Android AgentLoop.kt + 鸿蒙 AgentLoop.ets，forced 有内容→说明书进 system+开局长文语义+撤 use_skill/M2 工具；污染会话 @eli5 实测一次出画布（step1 单轮 21.6KB 零工具往返）；单测 +5、旧语义用例改自决路径 | ✅ 已实现；鸿蒙侧回归挂 P13 同期 | 20261001_fw_round1_ui（08 三次降级）+ 用户拍板 | 已收口（实现面）；鸿蒙回归挂 P13 |
| P15 | HyperOS「获取应用列表」权限闸：新侧载默认拒 → queryIntentActivities 只回自己 → 黑名单添加列表空（仅云开），用户无法经 UI 添加任何 app（2026-10-01 真机发现③；系统设置授权后即刻恢复完整列表） | MIUI 权限层；manifest `<queries>` 声明正确、模拟器无此层（09-16 验收通过的原因） | ✅ 空选单 UI 提示已落地（2026-10-01：仅剩云开自己时显示「获取应用列表」授权路径文案；不硬跳——组件名随 HyperOS 版本漂移） | 20261001_realdevice_round2 REPORT.md 发现③ | 已收口（实现面） |
| P16 | 悬浮球关闭方式空缺 + 删除圈拽出锚点跳变/动画器互写/拖拽竞态崩溃（门0 稳定性轮 W3-W5） | —— | **已收口（a529a5c）**：拖底删除圈（球心入圈红高亮、圈内松手缩小淡出关闭=sessionHidden 单次隐藏，重绑不复活、设置开关 ON 清标记回归）；W3 脱贴边帧重置锚点、W4 动画器句柄可取消、W5 safeUpdate 统一。模拟器全链实测（拖入圈关/重绑 0 overlay/零崩溃）。**余**：真机手感把玩 | 20261002 稳定性轮 + 用户拍板「拖到屏幕底部删除圈来删除」 | 已收口（真机手感把玩待用户） |
