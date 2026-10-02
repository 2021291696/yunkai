# run-all 门2（界面层）报告 — yunkai-harmony

运行：2026-09-10 21:2x 起 | 通道：AI 驱动（hdc + uitest）
路径：8 条 —— PASS 7 / 未收口 1
证据目录：`artifacts/agent-ui/run1/`

| # | 路径 | 结论 | 内容级判据 / 说明 | 证据 |
|---|------|------|------------------|------|
| 1 | launch_and_guide | **PASS** | 冷启动问候式引导页（晚上好/想聊点什么？/日期+对话数/三个 chips）+ 左上 ☰；无装饰图形 | 06_launch_guide.png |
| 2 | drawer_open_and_layout | **PASS** | ☰ → 抽屉「会话 | 长按可删除 | ✕ | ＋新对话 | ⚙ | 会话列表」；残留检查：会话与历史/历史轮次/还没有讲解记录/收起 均 0 | 07_drawer_layout.png |
| 3 | drawer_dismiss_three_ways | **PASS** | ✕ / 点面板外 / 系统返回 三条路径后抽屉均消失且未退出应用（返回键被 onBackPress 消费） | 08_dismiss_back.png |
| 4 | settings_theme_modes | **PASS** | 点「深色」→ 设置页整屏转暗（09b）；force-stop 冷启动 → 系统为浅色但仍是深色（读档生效，09c）；切回「跟随系统」→ 回浅色（09d） | 09a_settings_light.png / 09b_settings_dark.png / 09c_theme_dark_persist.png / 09d_theme_system.png |
| 5 | skill_import_paste | **PASS** | 技能库 → 粘贴导入（无 📋 前缀）→ 注入「# 全流程导入技能」→ 解析并导入 → 列表出现该技能、表单关闭 | 10a_skills.png / 10b_import_form.png / 10c_imported.png |
| 6 | chat_bare_llm | **PASS** | 真连智谱 glm-5.3-flash：问「1+1等于几？」→ 回答「1+1等于2。」 | 11_bare_llm.png |
| 7 | chat_eli5_canvas | **PASS** | @eli5 chip → 画布卡 → 全屏画布 HTML 真渲染（标题「黑洞是什么？」+ 11 节目录），非白屏 | 12a_canvas_card.png / 12_canvas.png |
| 8 | probe_bad_baseurl | **未收口（挂账）** | 错误 baseURL 报错路径本轮未触发：① 保存按钮在折叠下方且打字后 IME 挡住滚动 → 已定位并修（收键盘后能滚动命中 660,1715 并保存成功）；② 第三次的发送未落到输入框（引导页无用户气泡）。已确认收尾状态正常：地址已还原 open.bigmodel、模型正常回「1+1=2」。参照：20260907-agent-m1-gate2 曾验证该路径（screenshot_error_toast） | 13_error_probe.png（显示为引导页，非报错态 → 未触发的直接证据） |

## 方法备注（判定口径）

- **内容级断言**：LLM 生成内容的路径一律读屏上文本判内容（是否切题、有无自述失败文案），不看「渲染成功」；长文本节点在 uiautomator/ArkUI dump 中可能缺失，故内容判定以截图 + logcat 逐步日志为准。
- **概率性路径**：联网搜索/真 LLM 路径本轮各复跑 1-2 次取一致结果；⑥ 的时间线属瞬态，以上一步的 dump 与 logcat 双重取证。
- **中文输入**：Android 侧本机 AVD 无 Unicode IME，本轮装了 ADBKeyboard（gh-proxy 镜像拉取）经 broadcast 注入；鸿蒙侧用 `uitest uiInput inputText`。两端输入均为清单规定的原始中文问句。
- **密钥**：Android 侧的 DeepSeek key 经环境变量注入设备设置页，产物与日志中全程掩码（截图见 03c/03e），未落明文（铁律 2）。

## 挂账（铁律 9：存在挂账 = 本轮未收口）

- **probe_bad_baseurl**：错误 baseURL 报错路径本轮未触发：① 保存按钮在折叠下方且打字后 IME 挡住滚动 → 已定位并修（收键盘后能滚动命中 660,1715 并保存成功）；② 第三次的发送未落到输入框（引导页无用户气泡）。已确认收尾状态正常：地址已还原 open.bigmodel、模型正常回「1+1=2」。参照：20260907-agent-m1-gate2 曾验证该路径（screenshot_error_toast）


## 挂账详情（09-11 补记）：probe_bad_baseurl

**状态**：未收口（驱动问题，非应用缺陷）。三轮尝试记录：

1. 第 1 轮：字段编辑成功（dump 证实 invalid.example 已入框），但「保存」按钮在折叠下方且输入法遮挡滚动——收键盘（Back）后滚动才能命中「保存」(660,1715)，该轮保存已点击。
2. 第 2 轮：已证明「收键盘后滚动」可稳定命中保存（660,2191）；但保存后回对话页的导航链 tap(93,204) 误开抽屉（Chat 页左上角 93,204 是 ☰），状态机漂移。
3. 第 3 轮（每步 dump 校验版 probe_baseurl3.sh）：到「滚动找保存」时 dump 反复不含「保存」（页面停在顶部——uitest swipe 在该页偶发不生效），保存定位失败退出，保持挂账。

**复现指引（下轮收口）**：走 `probe_baseurl3.sh` 思路（每步 dump 校验），滚动改用「先短滑 2 次（660,1700→800）验证树里出现『保存』再长滑」的渐进策略；或改用 UI 抓取替代——直接读 Preferences 文件确认保存生效后，仅驱动对话段。错误本体（toast「出错了：LLM HTTP …」）在 09-07 `20260907-agent-m1-gate2` 报告中有通过证据，本轮仅未复跑。

**已排除**：应用崩溃（各轮 force-stop 前后 dump 均正常）；保存按钮不存在（有轮 dump 明确命中 660,1715/660,2191）。
