# 门0 全量静态审查报告（logic-review --full 口径）

- **日期**：2026-09-11
- **对象**：`yunkai-harmony` `entry/src/main/ets/` 全部 29 个 .ets 文件，共 **3665 行**（工作区干净，审查基线 HEAD=`1be8678` feat(M2): B3/B4）
- **方式**：全量通读 + 逐维静态审查（会崩溃 / 数据丢失损坏 / 安全 / 逻辑边界竞态状态机 / ArkTS 收窄与 undefined），重点覆盖 M2 变更：`LlmClient.chatStream`、`AgentLoop`、`M2Tools`、`HtmlGuard`、`ThemeMode`、`pages/Chat`
- **模式**：--full（代码维度 1-9；内容审查 6 维不适用：无 spec/页面产物在范围内，本报告为纯代码静态审查）；修复授权未给（只审不修）

---

## 结论

| 定级 | 条数 |
|------|------|
| **Blocking**（必须先修才能进动态测试） | **1** |
| **Important** | 5 |
| **Minor** | 13 |

**判定为 REQUEST_CHANGES**：存在 1 条 Blocking（流式解码缺 `stream:true`，中文内容分片边界必然损坏且会持久化入库），修复成本一行，修完即可进动态测试。

---

## Blocking

### B1. SSE 增量解码未开 stream 模式 → 跨分片多字节 UTF-8 字符损坏（UI 与入库双污染）

- **位置**：`entry/src/main/ets/service/LlmClient.ets:243`
- **证据**：
  ```ts
  const chunk = decoder.decodeToString(new Uint8Array(data));   // ← 无 { stream: true }
  ```
  第 143 行注释自称「用 TextDecoder(stream 模式) 增量解码」，但 `decodeToString` 第二参缺省为 `false`（每次调用独立终结）：上一分片末尾的**不完整多字节序列被替换为 U+FFFD**，下一分片开头的续字节再次独立解码再坏一次。`dataReceive` 回调按网络分片（任意字节边界）到达，与 UTF-8 边界无关。
- **影响**：中文每字 3 字节，分片边界切进字符的概率极高 → 流式气泡出现 `�` 乱码；且 `content` 累计串本身就是坏串，`dataEnd` 后 `resolve(msg)` 把损坏文本经 `Chat.send` → `MessageRepo.add` **永久入库**，历史轮次的 plain/HTML 提取全部带病。
- **为什么是 Blocking**：动态测试第一轮中文流式即复现、且污染持久层；修复是一行改动，先修再测成本最低。
- **修复建议**：`decoder.decodeToString(new Uint8Array(data), { stream: true })`；`dataEnd` 冲刷残行前补一次 `decodeToString(new Uint8Array(new ArrayBuffer(0)))`（stream 缺省 false）终结残余字节。

---

## Important

### I1. onDelta 传给了所有 LLM 步（含工具调度步），与「只传最终回答步」设计不符；limit 收尾反而完全不流式

- **位置**：`entry/src/main/ets/service/AgentLoop.ets:112`（`doChat(..., onDelta)` 对每一步生效）、`AgentLoop.ets:145`（limit 收尾 `doChat(messages, null)` 未传 onDelta）
- **证据**：工具调度步的模型常先流式吐前导文本（「让我搜索一下…」）再发 tool_calls；这段本应丢弃的前导会以「回答气泡」形态显示在流式气泡里并滞留到工具执行期，最终被下一步覆盖。收尾强制作答路径（步数到顶）反而无任何流式，期间气泡还挂着上一步的陈旧文本。
- **影响**：UI 误导（把中间过程当回答展示）+ 行为不一致；不崩溃、不损数据。
- **修复建议**：中间步不传 onDelta（仅无 tool_calls 的作答步其实无法预知，可用「本步已产 tool_calls 则回滚 streamText」或首轮清洗），limit 收尾路径补传 onDelta。

### I2. 取消不中断在途流式请求：isCancelled 只在步边界/工具间轮询，在途 chatStream 会跑完（最长 600s）

- **位置**：`entry/src/main/ets/service/AgentLoop.ets:63-67,108-119`（checkCancel 仅步首与逐工具前）；`AgentLoop.ets:100` 注释称「取消由调用方在 onDelta 里检查」，但 `pages/Chat.ets:193-197` 的 onDelta 只做 `gen !== this.genId` 丢增量，**不抛不通知**，LlmClient 也无 abort 通道。
- **证据**：用户点「取消」→ `cancelLoading()` 置 genId+1 → `AgentLoop` 仍在 `await doChat(...)` 内；LLM 未流完前旗标无人读。
- **影响**：与 AgentLoop 头注释「保证后台不再烧 token」不符——取消后当前流继续烧 token 最长 10 分钟；结果最终被丢弃，纯成本泄漏。UI 侧无感（loading 已 false）。
- **修复建议**：chatStream 暴露 `destroy()`/abort 句柄，或在 onDelta 里命中取消即抛 `AGENT_LOOP_CANCELLED` 由 LlmClient destroy 连接。

### I3. HtmlGuard.sanitize 存在三种可绕过形态（on*=/javascript:）；当前仅靠 javaScriptAccess(false) 单层兜底

- **位置**：`entry/src/main/ets/service/HtmlGuard.ets:30-34`
- **证据**（均为 HTML 解析器合法、正则不匹配的形态）：
  1. `<svg/onload=alert(1)>`——`on*` 三条正则均要求属性名前是 `\s`，`/` 分隔属性不匹配；
  2. `<img src="x"onerror="y">`——引号后紧跟属性名（HTML 合法，无需空白）同样漏过；
  3. `href="jav&#x09;ascript:alert(1)"`——实体编码解码后成 `jav\tascript:`，浏览器剥控制符后即 `javascript:`，伪协议正则按字面量匹配不到。
  另 `data:text/html` href 与 `<meta http-equiv=refresh>` 不在覆盖面。
- **影响**：sanitize 第一道防线可被穿透；唯一消费方 `CanvasCard.ets:58` / `Canvas.ets:65` 的 `javaScriptAccess(false)` 使上述载体实际不可执行，故不升 Blocking——但两道防线名义双保险、实际第一道是漏的，一旦未来任何消费点开 JS（或复用 sanitize 于别的容器）即成洞。
- **修复建议**：`on\w+` 前置字符放宽到 `[\s/"'\x60]`；属性值先做实体解码再匹配伪协议（或对 `href/src/action` 值统一做 URL scheme 白名单）。

### I4. read_file 无大小上限：`new ArrayBuffer(stat.size)` 一次分配，可被打爆内存/上下文

- **位置**：`entry/src/main/ets/service/tools/M2Tools.ets:42-52`（readTextFile），调用链 `AgentLoop.ets:122 → execTool` 全文回填无预算截断（`AgentLoop.ets:13-14` 已自挂账，但分配层也未防护）。
- **证据**：`stat.size` 为任意值（沙箱内文件由 write_file 或用户侧产生）；`new ArrayBuffer(超大值)` 抛 RangeError 可被 catch 收敛，但接近物理内存的分配会原生 OOM 崩进程（不可捕获）；即便读成，全文无截断直接 `return` 进 messages 回填，撑爆上下文/烧钱。
- **修复建议**：stat.size 超阈值（如 256KB）直接返回 error 或截断尾部注明；与 AgentLoop 挂账的「tool 结果预算截断」一并落。

### I5. 技能 description/content 未做任何注入防御即拼入 system prompt / 工具结果（代码已自认 M2 待办，此处正式挂账）

- **位置**：`entry/src/main/ets/service/AgentLoop.ets:11-12`（TODO 注释）、`AgentLoop.ets:194-214`（skillBlock 直拼）、`BuiltinTools.ets:139-157`（use_skill 全文回传）；入口 `SkillManage.ets:59-98` / `SkillImporter.ets` 无转义、无长度上限。
- **影响**：用户导入的 SKILL.md 或技能简介可夹带「忽略以上指令/调用 write_file 写敏感路径」类 prompt injection，随每轮 system prompt 常驻。M2 新增的 write_file/memory_save 使注入的副作用面变大（此前只有读操作）。
- **修复建议**：上线前至少做 description 长度截断 + 内容换行/指令关键词过滤，或把技能正文只放 tool 结果不放 system prompt。

---

## Minor

| # | 位置 | 问题 |
|---|------|------|
| M1 | `pages/Chat.ets:136,152-156,234-239` | 发送失败路径状态不一致：input 已清空不回填（配置缺失时用户要重打全文）；catch 路径用户气泡残留 UI 但未入库，刷新后消失，与「净空语义」注释不符 |
| M2 | `pages/Chat.ets:127-130` | `cancelLoading` 不清 `streamText` → 下一轮发送在首个 delta 前短暂显示上一轮答案（下一轮为工具步且无 content 时滞留更久） |
| M3 | `service/tools/M2Tools.ets:168` | `all[key]=value` 在 key=`__proto__` 时赋进原型而非自有属性 → 返回 `saved:true` 但数据丢失（JSON.parse 产物为普通对象） |
| M4 | `service/tools/M2Tools.ets:68-80,169` | 记忆写盘非原子（TRUNC 后写），进程中断即 JSON 损坏 → `loadMemory` 静默清空整库重开；建议 tmp+rename 或 .bak |
| M5 | `service/AgentLoop.ets:184-187` | 抛出物非 Error 时 `(err as Error).message` 为 undefined → `JSON.stringify({error:undefined})` 丢字段回 `'{}'`，模型看不到原因（M2Tools/BuiltinTools 的 catch 是模板串，只显示 "undefined" 不丢 JSON） |
| M6 | `pages/Settings.ets:86-95` | `collect()` 未带上已存的 `longModel` → 每次保存把偏好里的 longModel 重置回默认 `'glm-4.7'`（当前无 UI 改它，属潜伏回退） |
| M7 | `store/Db.ets:36-41` + `store/MessageRepo.ets:36` + `pages/Chat.ets:99` | `kind` 列只写不读（渲染重建走 `ReplyKind.detect`）；sanitize 失败（如 >300KB）的轮次入库为 TEXT 但 content 以 `<html` 开头，重载后被 detect 判成画布卡 → 显示「内容无法渲染」，与当轮 TEXT 气泡两态不一致 |
| M8 | `pages/Chat.ets:298-306` | loading 中抽屉仍可长按删除当前会话 → 在途回答照常落库成孤儿消息行（conversation_id 指向已删会话，无 FK 级联，永久残留） |
| M9 | `service/HtmlGuard.ets:25,38` | `lower` 在剥 script 前计算，viewport 探测可能被 `<script>` 内同名文本误满足而跳过注入（纯显示层） |
| M10 | `service/SearchRouter.ets` 全文件、`model/Types.ets:74`、`pages/Chat.ets:51,112` | SearchRouter/NetMode 无任何调用方（M1 遗留死代码）；`turns`/`refreshTurns` 写后不再被消费；Chat.ets:2 头注释「历史轮次双段」与 HistoryDrawer 实现不符 |
| M11 | `store/ConversationRepo.ets:56`、`store/ConfigStore.ets:11`、`service/LlmClient.ets:48-50` | 会话标题 `substring(0,20)` 可截断代理对出乱码；apiKey 明文存偏好（建议关键资产库）；baseUrl 无 scheme 校验，填 `http://` 时密钥明文过网 |
| M12 | `entryability/EntryAbility.ets:14` | `seedIfEmpty` 异步播种与首条消息的 skillBlock 竞态：极快首问时技能清单可能为空（下一问自愈） |
| M13 | `service/LlmClient.ets:278-286`、`LlmClient.ets:181-185` | 流式非 2xx 丢弃响应体（错误只有状态码，chatMessage 有 body 前 300 字，不对称）；`onDelta` 每片传全量累计串，16K token 下 O(n²) 字符串拷贝+整段重渲染 |

---

## 关键 PASS（抽查证据）

| 检查项 | 结论 | 证据 |
|--------|------|------|
| safeJoin 沙箱 | PASS | `M2Tools.ets:33-38`：拒 `''`/前导 `/`/含 `..`（含内嵌段 `a/../b`）；记忆文件在 filesDir 根不在 agent_files 内，read_file 触不到 |
| chatStream 三路终止 + 防双 resolve | PASS | `LlmClient.ets:152-179`：`done` 旗标守卫 `[DONE]`（221）/httpErr（164-166,280-281）/dataEnd（250-268）互斥，`hr.destroy()` 单点（163） |
| tool_calls 分片按 index 组装 | PASS | `LlmClient.ets:154-208`：id/name 只取首片不覆盖（194-202），arguments 顺序拼接（204-206），name 缺失不下行（171-172），id 缺省合成 `call_${idx}`（170）保证 assistant/tool 消息 id 配对 |
| 非 2xx / 异常路径 | PASS | `chatMessage:105-107`、`listModels:126-128` 均先判 responseCode 再解析；所有 http 实例 finally destroy（109-111,133-135 等） |
| ThemeMode 双枚举不互赋 | PASS | `ThemeMode.ets:17-41`：经 `configIsDark` 布尔中转；`effectiveDark` 手动档短路（23-24）不看可能滞后的 ctx.config；`applyThemeMode` 失败留 hilog（38） |
| Chat 取消后不落库 | PASS | `pages/Chat.ets:200` `if (gen !== this.genId) { return; }` 在 create/add 之前；取消与失败路径均无会话/消息写入 |
| SQL 注入面 | PASS | 全部经 `RdbPredicates.equalTo` 参数化（Db/MessageRepo/ConversationRepo/SkillRepo），唯二裸 SQL 是无参 DDL（Db.ets:16-35,38） |
| ResultSet 泄漏 | PASS | 四个 Repo 的结果集全部 `try { … } finally { rs.close() }`（Db.ets:52-61、MessageRepo.ets:50-64、ConversationRepo.ets:27-38、SkillRepo.ets:17-23） |
| Canvas 双消费点 JS 关闭 | PASS | `CanvasCard.ets:58`、`Canvas.ets:65` 均 `.javaScriptAccess(false)`（I3 的兜底层成立） |
| 工具失败不崩循环 | PASS | `AgentLoop.execTool:170-188` 未知名/异常统一收敛 `{"error":...}`；M2Tools 四工具全部 try/catch 收敛（M2Tools.ets:104-204） |
| forIn/ArkTS 收窄 | PASS | 全仓对象字面量均对应显式 interface；`p['convId']`/`m[1]` 等 undefined 取值均有守卫（Chat.ets:66-71,162）；未见 any/裸 as Object 误用 |

---

## 未执行维度说明

- 内容审查 6 维（设计合理性/页面可开/视觉质量/需求完整性/一致性/残留物）：本任务范围为纯代码门0，无 spec/网页产物在审查区间内，不适用。
- 维度 10（技术选型）：无新技术选型 plan 在区间内，不适用。
- 深度安全专项（security skill 升级）：I3/I5 属已定位的应用层注入面，未涉认证体系/依赖漏洞，未升级。

---

## 修复闭环提示

可自动修复（白名单内）0 项——B1/I1-I5 均改变行为或需取舍（截断阈值、取消语义、注入策略），需人工确认方案后再动。

**建议顺序**：B1（一行）→ I2（取消语义，影响动态测试观感）→ I4（防崩）→ I3/I5（安全，可随 M2 收尾）→ I1（UI 一致性）。
