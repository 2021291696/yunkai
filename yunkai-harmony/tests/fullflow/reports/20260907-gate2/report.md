# run-all 全流程测试报告 — 云开（eli5-harmony）
运行时间：2026-09-07 上午 ｜ 平台：HarmonyOS NEXT 模拟器 eli5_emulator（127.0.0.1:5555）
清单：tests/fullflow/manifest.yaml ｜ 执行：ZCode AI 编排（铁律1 账单已出，用户 goal 明示直接跑）

## 三门结论

| 门 | 结论 | 摘要 |
|---|---|---|
| 门0 静态审查 | **PASS**（0 blocking / 3 warning） | 1557 行 ets 全量审；W1 key 明文存本地沙箱（MVP 接受）、W2 失败轮留 user 消息、W3 Db 单例 |
| 门1 构建/单测 | **PASS** | clean 双构建 SUCCESSFUL；logicTest **19/19**（signed 主包+signed 测试包组合） |
| 门2 AI 驱动 UI | **5 条路径：4 PASS / 1 部分完成** | 见下 |

## 门2 逐路径（证据在 artifacts/agent-ui/run20260907/）

| 路径 | 结果 | 证据 |
|---|---|---|
| P2 核心链（新对话→真连智谱→eli5 渲染） | **PASS** | P2_rendered.jpeg：极光讲解卡片+SVG，玻璃化 UI 融合 |
| P3 草稿探针（新对话即退） | **PASS** | P3_list_after.jpeg：列表无空记录新增 |
| P4 追问承接 | **PASS** | P4_followup.jpeg：「在南极看极光」承接首轮语境 |
| P5 必应联网轨 | **部分完成（挂账）** | 一次尝试 110s 失败无 toast 留痕；两次复现被模拟器 UI 自动化 Back 误触打断 |
| P6 错误路径探针 | **部分完成（挂账）** | 早前 E2E 已验证坏 URL 走报错分支（逻辑分支存在），本轮 toast 取证未完成 |

## 根因分析（P5 失败轮）

现象：联网轮 110 秒后遮罩消失、无新增轮次（历史抽屉取证 P5_history_check.jpeg 只有 2 轮）。
线索：①PC curl 同款必应请求 200/102KB 正常；②失败时间窗（约 110s）与「必应 20s readTimeout 抛错→LLM 继续生成」或「LLM 侧失败」均不完全吻合；③hilog 未捕获应用层网络错误（ArkTS throw 不落日志）。
假设（按可能性）：①模拟器网络出口访问 cn.bing.com 触发风控/超时（模拟器与 PC 出口不同）；②LLM 服务端对该请求偶发 5xx。
修复建议：Chat.ets catch 分支补 hilog.error 输出异常信息（一行代码，下轮带上），即可定位；必应解析器正确性已由单测锁定，无需重写。

## 遗留

1. P5/P6 完整取证：建议用户在模拟器/真机上手动跑一次联网新闻题观察（自动化 Back 误触是主因）
2. 真机终验：手机连 USB → DevEco Run（签名体系已就绪）
3. 重进会话恢复渲染：时序修复已提交，日常使用观察
