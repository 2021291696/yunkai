# 云开（eli5-harmony）

自用的鸿蒙 HarmonyOS NEXT **桌面 agent**（手机版 zcode）：打开即对话，agent 自主多步调用工具（联网搜索/网页阅读/技能），回答按需呈现为**聊天气泡**或**整页 HTML 画布**； eli5 是内置技能之一。

## 功能

- **Agent loop**：模型多步工具调用循环（步数上限 10，过程时间线实时可见，可取消）；工具失败自动回传模型自适应
- **技能库**：内置 eli5（大图少字整页讲解，配方对齐桌面端产出质量）+ 新建 + 粘贴导入 SKILL.md；@技能名 强制指定，自动路由可在设置关闭
- **API 自配**：OpenAI 兼容三项（baseURL / apiKey / model），「获取模型列表」一键拉取下拉选择；双模型分工（主模型调度 + 长文自动切换）
- **联网搜索**：必应爬取（免 key，本地直连 cn.bing.com，自动做搜索词关键词提取）/ 博查 / Tavily
- **多轮对话**：历史只回传「问题+纯文本要点」控成本；本地 RDB 存储，重进恢复，长按删除会话；点新对话即退不留空记录
- **快捷问题**：引导页三个点击直发的示例问题（含 @eli5 语法演示）
- **兜底硬化**：非合法 HTML 自动重试语义内置于 agent 循环；渲染前剥 `<script>` 块（讲解页无需 JS）

## 构建

```bash
# DevEco Studio 26+ 打开工程直接 Run；或 CLI：
DEVECO_SDK_HOME='D:\Huawei\DevEcoStudio\sdk' node 'D:\Huawei\DevEcoStudio\tools\hvigor\bin\hvigorw.js' --mode module -p product=default assembleHap
```

模拟器免签名装 unsigned 包即可；签名包（真机用）在 `entry/build/default/outputs/default/entry-default-signed.hap`（签名在 File > Project Structure > 签名配置 开自动签名，签过一次工程内会带 signingConfigs）。

## 使用

1. 设置页填 baseURL / API key / 模型名（首次建议 `https://open.bigmodel.cn/api/paas/v4` + 智谱 key；主模型建议 `glm-5.3-flash`——对话与工具调度快）
2. 搜索源默认「必应(免key)」，无需额外注册
3. 联网搜索由 agent 自主决定何时调用（新闻/时效性问题自动搜），无需手动开关
4. **双模型分工**：@eli5 等技能触发的长文生成自动切换 `glm-4.7`（AppConfig.longModel，可改），因 glm-5.3-flash 始终深度思考、长页输出会吃满 token 预算

## 已知事项

- 讲解生成通常 1-3 分钟（非流式，agent 多步+长文，可点取消）
- API key 只存设备本地 Preferences，不进仓库
