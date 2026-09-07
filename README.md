# 云开（eli5-harmony）

自用的鸿蒙 HarmonyOS NEXT 讲解 app：提问后由你自配的大模型以 **eli5 方式**（整页 HTML、大图少字、内嵌 SVG 插图）回答，WebView 渲染；带**联网搜索**与**多轮追问**。

## 功能

- **API 自配**：OpenAI 兼容三项（baseURL / apiKey / model），「获取模型列表」一键拉取下拉选择
- **联网搜索双轨**：必应爬取（免 key，本地直连 cn.bing.com，自动做搜索词关键词提取）/ 博查 / Tavily；智谱系模型可选用内置 `web_search` 工具（注意：实测 glm-4.7 不执行该工具，glm-4.5 可用）
- **多轮追问**：历史只回传「问题+纯文本要点」控成本；模型需要某轮全文时输出 `[[READ_HTML:轮次号]]` 由 app 注入重发（最多一次）
- **历史会话**：本地 RDB 存储，重进恢复渲染，长按删除；点新对话即退不留空记录
- **快捷问题**：引导页三个点击直发的示例问题
- **eli5 兜底**：非合法 HTML 自动重试一次；渲染前剥 `<script>` 块（讲解页无需 JS）

## 构建

```bash
# DevEco Studio 26+ 打开工程直接 Run；或 CLI：
DEVECO_SDK_HOME='D:\Huawei\DevEcoStudio\sdk' node 'D:\Huawei\DevEcoStudio\tools\hvigor\bin\hvigorw.js' --mode module -p product=default assembleHap
```

模拟器免签名装 unsigned 包即可；签名包（真机用）在 `entry/build/default/outputs/default/entry-default-signed.hap`（签名在 File > Project Structure > 签名配置 开自动签名，签过一次工程内会带 signingConfigs）。

## 使用

1. 设置页填 baseURL / API key / 模型名（首次建议 `https://open.bigmodel.cn/api/paas/v4` + 智谱 key）
2. 搜索源默认「必应(免key)」，无需额外注册
3. 对话页底部「🌐 联网」胶囊控制每轮是否联网（开=橙色）

## 已知事项

- 讲解生成通常 30-90 秒（非流式，可点取消）
- API key 只存设备本地 Preferences，不进仓库
