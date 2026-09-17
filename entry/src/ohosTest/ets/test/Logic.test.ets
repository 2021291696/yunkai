import { describe, it, expect } from '@ohos/hypium';
import AbilityDelegatorRegistry from '@ohos.app.ability.abilityDelegatorRegistry';
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { HtmlGuard, ReplyKind } from '../../../main/ets/service/HtmlGuard';
import { HtmlExtractor } from '../../../main/ets/service/HtmlExtractor';
import { SearchRouter } from '../../../main/ets/service/SearchRouter';
import { fileIo as fs, fileUri } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { AppConfig, AgentSkill, ChatMsg, ContentPart, Msg, ToolCall, ToolDef } from '../../../main/ets/model/Types';
import { Bm25, Doc } from '../../../main/ets/memory/Bm25';
import { PrivacyGate } from '../../../main/ets/memory/PrivacyGate';
import { Migrator } from '../../../main/ets/memory/Migrator';
import { Summarizer, Span, stripTraceForPersist } from '../../../main/ets/memory/Summarizer';
import { AttachUtil } from '../../../main/ets/service/AttachUtil';
import { DocTextExtractor } from '../../../main/ets/service/DocTextExtractor';
import { zlib } from '@kit.BasicServicesKit';
import { LlmClient, OpenAiMessage } from '../../../main/ets/service/LlmClient';
import { AgentLoop, LoopEvent } from '../../../main/ets/service/AgentLoop';
import { SearchClient } from '../../../main/ets/service/SearchClient';
import { Db } from '../../../main/ets/store/Db';
import { ConfigStore } from '../../../main/ets/store/ConfigStore';
import { SkillRepo } from '../../../main/ets/store/SkillRepo';
import { SkillImporter } from '../../../main/ets/service/SkillImporter';
import { AgentTool } from '../../../main/ets/service/tools/AgentTool';
import { BuiltinTools } from '../../../main/ets/service/tools/BuiltinTools';
import { ScreenBlacklist, ScreenFormat, ScreenGuard, ScreenNode } from '../../../main/ets/service/screen/ScreenLogic';

// ===== M5a 记忆引擎测试辅助 =====

// 读向量 JSON（rawfile/vectors 下三份文件与 yishu 仓 protocol/vectors md5 逐字对拍一致）
async function readVectorJson(name: string): Promise<Record<string, Object>> {
  const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext();
  const raw: Uint8Array = await ctx.resourceManager.getRawFileContent(`vectors/${name}`);
  const text: string = util.TextDecoder.create('utf-8').decodeToString(raw);
  return JSON.parse(text) as Record<string, Object>;
}

// Summarizer 用例构造（同构 Kotlin SummarizerTest.mkRows）：
// n 轮（每轮 user+assistant，assistant turn_no=1..n 递增），行序 u1,a1,u2,a2,...
function mkRows(n: number): Msg[] {
  const rows: Msg[] = [];
  for (let i = 1; i <= n; i++) {
    const u = new Msg();
    u.id = rows.length + 1; u.convId = 1; u.role = 'user'; u.content = `问${i}`;
    u.plain = ''; u.turnNo = 0; u.createdAt = i * 10;
    rows.push(u);
    const a = new Msg();
    a.id = rows.length + 1; a.convId = 1; a.role = 'assistant'; a.content = `答${i}`;
    a.plain = `答${i}`; a.turnNo = i; a.createdAt = i * 10 + 1;
    rows.push(a);
  }
  return rows;
}

export default function logicTest() {
  describe('logicTest', () => {
    it('guard_accepts_full_doc', 0, () => {
      expect(HtmlGuard.sanitize('<!DOCTYPE html><html><body>hi</body></html>') !== null).assertTrue();
    });
    it('guard_strips_markdown_fence_and_injects_viewport', 0, () => {
      const raw = '```html\n<!doctype html><html><head><title>t</title></head><body>hi</body></html>\n```';
      const out = HtmlGuard.sanitize(raw);
      expect(out !== null).assertTrue();
      expect(out !== null && out.includes('name="viewport"')).assertTrue();
    });
    it('guard_rejects_non_html', 0, () => {
      expect(HtmlGuard.sanitize('抱歉我无法输出')).assertNull();
    });
    it('extractor_strips_tags_and_entities', 0, () => {
      const plain = HtmlExtractor.stripTags('<style>.a{}</style><h1>电 &amp; 磁</h1><p>讲解</p>');
      expect(plain.includes('电 & 磁')).assertTrue();
      expect(plain.includes('<h1>')).assertFalse();
    });
    it('reply_kind_detects_html', 0, () => {
      expect(ReplyKind.detect('<!DOCTYPE html><html></html>') === 'html').assertTrue();
      expect(ReplyKind.detect('普通文本回答') === 'text').assertTrue();
    });
    it('reply_kind_detects_html_prefix_case_insensitive', 0, () => {
      expect(ReplyKind.detect('<!doctype HTML><html><body>x</body></html>') === 'html').assertTrue();
      expect(ReplyKind.detect('<html><body>裸html前缀</body></html>') === 'html').assertTrue();
    });
    it('reply_kind_detects_text_with_inline_markup', 0, () => {
      expect(ReplyKind.detect('<div>非文档的行内标签片段</div>') === 'text').assertTrue();
    });
    it('router_auto_default_bing_external', 0, () => {
      const c = new AppConfig();
      c.baseUrl = 'https://open.bigmodel.cn/api/paas/v4';
      expect(SearchRouter.resolve(c) === 1).assertTrue();
    });
    it('router_auto_zhipu_fallback_builtin', 0, () => {
      const c = new AppConfig();
      c.baseUrl = 'https://open.bigmodel.cn/api/paas/v4';
      c.searchProvider = 'bocha';
      expect(SearchRouter.resolve(c) === 0).assertTrue();
    });
    it('router_auto_external_without_zhipu', 0, () => {
      const c = new AppConfig();
      c.baseUrl = 'https://api.deepseek.com/v1';
      c.searchProvider = 'bocha';
      c.searchApiKey = 'sk-x';
      expect(SearchRouter.resolve(c) === 1).assertTrue();
    });
    it('router_auto_none_without_key', 0, () => {
      const c = new AppConfig();
      c.baseUrl = 'https://api.deepseek.com/v1';
      c.searchProvider = 'bocha';
      expect(SearchRouter.resolve(c) === 2).assertTrue();
    });
    it('request_body_plain', 0, () => {
      const msgs: ChatMsg[] = [{ role: 'system', content: 's' }, { role: 'user', content: 'q' }];
      const body = LlmClient.buildRequestBody('glm-4.7', msgs, null);
      expect(body.includes('"model":"glm-4.7"')).assertTrue();
      expect(body.includes('"stream":false')).assertTrue();
      expect(body.includes('web_search')).assertFalse();
    });
    it('request_body_with_tools_and_max_tokens', 0, () => {
      const msgs: ChatMsg[] = [{ role: 'user', content: 'q' }];
      const body = LlmClient.buildRequestBody('glm-4.7', msgs, null);
      expect(body.includes('"max_tokens":16384')).assertTrue();
      expect(body.includes('"tools"')).assertFalse();
    });
    it('request_body_tool_defs', 0, () => {
      const msgs: ChatMsg[] = [{ role: 'user', content: 'q' }];
      const td: ToolDef[] = [{ type: 'function', function: { name: 'web_search', description: '搜索', parameters: { type: 'object', properties: {} } } }];
      const body = LlmClient.buildRequestBody('glm-4.7', msgs, td);
      expect(body.includes('"web_search"')).assertTrue();
      expect(body.includes('"type":"function"')).assertTrue();
    });
    it('extract_message_parses_tool_calls', 0, () => {
      const j = '{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\\"query\\":\\"AI\\"}"}}]}}]}';
      const m = LlmClient.extractMessage(j);
      expect(m.tool_calls !== undefined && m.tool_calls!.length === 1).assertTrue();
      expect((m.tool_calls as ToolCall[])[0].function.name === 'web_search').assertTrue();
    });
    it('extract_message_null_content_normalized', 0, () => {
      // tool_calls 轮普遍返回 content:null → 归一为 ''，tool_calls 原样保留
      const j = '{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{}"}}]}}]}';
      const m = LlmClient.extractMessage(j);
      expect(m.content === '').assertTrue();
      expect(m.tool_calls !== undefined && m.tool_calls!.length === 1).assertTrue();
    });
    it('extract_message_tool_calls_non_array_guarded', 0, () => {
      // 畸形响应：tool_calls 非数组（字符串）→ 视为未携带，content 照常返回
      const j = '{"choices":[{"message":{"role":"assistant","content":"纯文本","tool_calls":"oops"}}]}';
      const m = LlmClient.extractMessage(j);
      expect(m.content === '纯文本').assertTrue();
      expect(m.tool_calls === undefined).assertTrue();
    });
    it('parse_bocha_hits', 0, () => {
      const j = '{"code":200,"data":{"webPages":{"value":[{"name":"安全电压","url":"https://a.b","summary":"36V以下"},{"name":"t2","url":"https://c.d","snippet":"snip"}]}}}';
      const hits = SearchClient.parseBocha(j);
      expect(hits.length === 2).assertTrue();
      expect(hits[0].snippet === '36V以下').assertTrue();
    });
    it('parse_tavily_hits', 0, () => {
      const j = '{"results":[{"title":"安全电压","url":"https://a.b","content":"36V以下"},{"title":"t2","url":"https://c.d","content":"x"}]}';
      const hits = SearchClient.parseTavily(j);
      expect(hits.length === 2).assertTrue();
      expect(hits[0].title === '安全电压').assertTrue();
    });
    it('parse_bing_hits', 0, () => {
      const html = '<li class="b_algo"><h2 class=""><a target="_blank" href="https://baike.baidu.com/item/x" h="ID=SERP"><strong>安全电压</strong>_百度百科</a></h2><div class="b_caption"><p class="b_lineclamp2">安全电压是指不致使人直接致死或致残的电压&#0183;一般36V&ensp;以下</p></div></li><li class="b_algo"><h2><a href="https://zhihu.com/p/1">知乎讨论</a></h2><div><p>另一种说法</p></div></li>';
      const hits = SearchClient.parseBing(html);
      expect(hits.length === 2).assertTrue();
      expect(hits[0].url === 'https://baike.baidu.com/item/x').assertTrue();
      expect(hits[0].title.includes('安全电压')).assertTrue();
      expect(hits[0].snippet.includes('36V') && hits[0].snippet.includes('·')).assertTrue();
    });
    it('router_bing_without_key_is_external', 0, () => {
      const c = new AppConfig();
      c.baseUrl = 'https://api.deepseek.com/v1';
      c.searchProvider = 'bing';
      expect(SearchRouter.resolve(c) === 1).assertTrue();
    });
    it('extract_keywords_strips_question_words', 0, () => {
      const k = SearchClient.extractKeywords('这周国际上有什么科技大事');
      expect(k.includes('科技')).assertTrue();
      expect(k.includes('什么')).assertFalse();
      expect(k.includes('这周')).assertFalse();
    });
    it('extract_keywords_fallback_when_all_stopwords', 0, () => {
      const k = SearchClient.extractKeywords('为什么呀');
      expect(k === '为什么呀').assertTrue();
    });
    it('guard_strips_script_tags', 0, () => {
      const doc = '<!DOCTYPE html><html><head><title>t</title></head><body><script>alert(1)<\/script><p>正文</p></body></html>';
      const out = HtmlGuard.sanitize(doc);
      expect(out !== null).assertTrue();
      if (out !== null) {
        expect(out.includes('<script')).assertFalse();
        expect(out.includes('正文')).assertTrue();
      }
    });
    it('guard_strips_inline_event_handlers', 0, () => {
      const doc = '<!DOCTYPE html><html><body><img src="x" onerror="alert(1)"><div onclick="x()"><p>正文</p></div>';
      const out = HtmlGuard.sanitize(doc);
      expect(out !== null).assertTrue();
      expect(out!.includes('onerror')).assertFalse();
      expect(out!.includes('onclick')).assertFalse();
      expect(out!.includes('<p>正文</p>')).assertTrue();
    });
    it('guard_strips_javascript_protocol_urls', 0, () => {
      const doc = '<!DOCTYPE html><html><body><a href="javascript:doIt()">go</a><img src=javascript:x()>';
      const out = HtmlGuard.sanitize(doc);
      expect(out !== null).assertTrue();
      expect(out!.includes('javascript:')).assertFalse();
    });
    it('guard_strips_iframe_blocks', 0, () => {
      const doc = '<!DOCTYPE html><html><body><iframe src="https://evil.example"></iframe><p>正文</p>';
      const out = HtmlGuard.sanitize(doc);
      expect(out !== null).assertTrue();
      expect(out!.includes('iframe')).assertFalse();
      expect(out!.includes('<p>正文</p>')).assertTrue();
    });
    it('guard_keeps_normal_links_and_images', 0, () => {
      const doc = '<!DOCTYPE html><html><body><a href="https://example.com">l</a><img src="pic.png">';
      const out = HtmlGuard.sanitize(doc);
      expect(out !== null).assertTrue();
      expect(out!.includes('https://example.com')).assertTrue();
      expect(out!.includes('pic.png')).assertTrue();
    });
    it('skill_repo_crud_and_seed', 0, async () => {
      // 注：getAppContext() 返回的模块级 Context 会被 getRdbStore 原生校验拒绝(401)，
      // 必须升到 ApplicationContext 才能建库。
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      await Db.get(ctx); // 建表
      await SkillRepo.seedBuiltins(ctx);
      // 上轮用例若中途中断会残留「测试技能」行，先清掉避免撞 UNIQUE
      const leftover = await SkillRepo.getByName(ctx, '测试技能');
      if (leftover !== null) {
        await SkillRepo.remove(ctx, leftover.id);
      }
      const all = await SkillRepo.list(ctx);
      expect(all.length >= 1).assertTrue();
      const eli5 = await SkillRepo.getByName(ctx, 'eli5');
      expect(eli5 !== null && eli5!.content.includes('章节')).assertTrue();
      const nid = await SkillRepo.insert(ctx, '测试技能', '测试描述', '正文', false);
      expect(nid > 0).assertTrue();
      await SkillRepo.remove(ctx, nid);
      expect((await SkillRepo.list(ctx)).length === all.length).assertTrue();
    });
    it('skill_repo_insert_duplicate_name_returns_minus_1', 0, async () => {
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      await Db.get(ctx);
      const before = await SkillRepo.list(ctx);
      const nid = await SkillRepo.insert(ctx, '重名测试技能', '描述', '正文', false);
      expect(nid > 0).assertTrue();
      // name UNIQUE 冲突：insert 收敛为 -1，表内数量不变
      const dup = await SkillRepo.insert(ctx, '重名测试技能', '描述', '正文', false);
      expect(dup === -1).assertTrue();
      await SkillRepo.remove(ctx, nid);
      expect((await SkillRepo.list(ctx)).length === before.length).assertTrue();
    });
    it('importer_parses_frontmatter', 0, () => {
      const md = '---\nname:翻译\ndescription:中英互译\n---\n# 翻译\n你是翻译官。';
      const s = SkillImporter.parse(md);
      expect(s !== null && s!.name === '翻译' && s!.description === '中英互译' && s!.content.includes('翻译官')).assertTrue();
    });
    it('importer_parses_chinese_name_multiline_frontmatter', 0, () => {
      // 狗头军师内置技能同构：中文 name + 多行 frontmatter
      const md = '---\nname: 狗头军师\ndescription: 恋爱军师与情绪支持。先接住情绪，再分清事实，最后给能执行的选择。\n---\n先接住情绪。';
      const s = SkillImporter.parse(md);
      expect(s !== null && s!.name === '狗头军师' && s!.description.startsWith('恋爱军师') && s!.content.includes('先接住情绪')).assertTrue();
    });
    it('importer_fallback_no_frontmatter', 0, () => {
      const s = SkillImporter.parse('# 写作助手\n帮你写文章。');
      expect(s !== null && s!.name === '写作助手' && s!.content.length > 0).assertTrue();
    });
    it('importer_rejects_empty', 0, () => {
      expect(SkillImporter.parse('   ') === null).assertTrue();
    });
    it('use_skill_tool_returns_content', 0, async () => {
      // 注：getAppContext() 返回的模块级 Context 会被 getRdbStore 原生校验拒绝(401)，
      // 必须升到 ApplicationContext 才能建库。
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      await Db.get(ctx); await SkillRepo.seedBuiltins(ctx);
      const tools = BuiltinTools.createAll(ctx, new AppConfig(), null);
      let eli5Tool: AgentTool | null = null;
      for (const t of tools) { if (t.name === 'use_skill') { eli5Tool = t; } }
      const out = await eli5Tool!.execute('{"name":"eli5"}');
      expect(out.includes('章节')).assertTrue();
      // 第二内置技能：狗头军师按名可取（seedBuiltins 双技能播种回归）
      const gt = await SkillRepo.getByName(ctx, '狗头军师');
      expect(gt !== null && gt!.content.includes('先接住情绪')).assertTrue();
    });
    it('tool_error_becomes_error_json', 0, async () => {
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      const tools = BuiltinTools.createAll(ctx, new AppConfig(), null);
      let readWeb: AgentTool | null = null;
      for (const t of tools) { if (t.name === 'read_web') { readWeb = t; } }
      const out = await readWeb!.execute('{"url":"http://127.0.0.1:1/x"}');
      expect(out.includes('"error"')).assertTrue();
    });
    it('web_search_guides_when_key_missing', 0, async () => {
      // bocha/tavily 且 key 为空：返回引导性文案（含设置页/免key提示），不发裸 401 error
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      const cfg = new AppConfig();
      cfg.searchProvider = 'bocha';
      cfg.searchApiKey = '';
      const tools = BuiltinTools.createAll(ctx, cfg, null);
      let ws: AgentTool | null = null;
      for (const t of tools) { if (t.name === 'web_search') { ws = t; } }
      const out = await ws!.execute('{"query":"AI"}');
      expect(out.includes('搜索密钥')).assertTrue();
      expect(out.includes('必应')).assertTrue();
      // bing 免 key 轨不受密钥引导影响（走真实网络仅断言非引导文案路径，0 hits 也只是普通文本）
      const cfgBing = new AppConfig();
      cfgBing.searchProvider = 'bing';
      const toolsBing = BuiltinTools.createAll(ctx, cfgBing, null);
      let wsBing: AgentTool | null = null;
      for (const t of toolsBing) { if (t.name === 'web_search') { wsBing = t; } }
      const outBing = await wsBing!.execute('{"query":"x"}');
      expect(outBing.includes('未配置搜索密钥')).assertFalse();
    });
    it('loop_executes_tool_then_answers', 0, async () => {
      // fake：第1轮回 tool_calls(web_search)，第2轮回文本答案
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      const tc: ToolCall = { id: 'call_1', type: 'function', function: { name: 'web_search', arguments: '{"query":"AI"}' } };
      const m1: OpenAiMessage = { content: '', tool_calls: [tc] };
      const m2: OpenAiMessage = { content: '好的' };
      const script: OpenAiMessage[] = [m1, m2];
      let idx = 0;
      const events: string[] = [];
      const r = await AgentLoop.run(new AppConfig(), ctx, [], '今天AI新闻', null,
        (e: LoopEvent) => { events.push(e.kind + ':' + e.toolName); },
        async (_messages: ChatMsg[], _tools: ToolDef[] | null): Promise<OpenAiMessage> => {
          const m = script[idx];
          idx++;
          return m;
        });
      expect(r.answer === '好的').assertTrue();
      expect(events[0] === 'tool_start:web_search').assertTrue();
      expect(events.indexOf('tool_done:web_search') >= 0).assertTrue();
      expect(r.steps === 2).assertTrue();
    });
    it('loop_tool_error_feeds_back_not_crash', 0, async () => {
      // fake：第1轮 tool_calls(read_web 指向必失败 URL)，fake 第2轮校验 messages 里有 {"error"}
      // 断言：不抛异常，最终 answer 正常
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      const tc: ToolCall = { id: 'call_err', type: 'function', function: { name: 'read_web', arguments: '{"url":"http://127.0.0.1:1/x"}' } };
      const m1: OpenAiMessage = { content: '', tool_calls: [tc] };
      const m2: OpenAiMessage = { content: '该网页暂时无法访问' };
      const script: OpenAiMessage[] = [m1, m2];
      let idx = 0;
      let sawErrorToolMsg = false;
      const r = await AgentLoop.run(new AppConfig(), ctx, [], '读这个网页', null,
        (_e: LoopEvent) => {},
        async (messages: ChatMsg[], _tools: ToolDef[] | null): Promise<OpenAiMessage> => {
          if (idx === 1) {
            for (const m of messages) {
              if (m.role === 'tool' && m.content.includes('{"error"')) { sawErrorToolMsg = true; }
            }
          }
          const m = script[idx];
          idx++;
          return m;
        });
      expect(sawErrorToolMsg).assertTrue();
      expect(r.answer === '该网页暂时无法访问').assertTrue();
      expect(r.steps === 2).assertTrue();
    });
    it('loop_step_limit_truncates', 0, async () => {
      // fake：永远回 tool_calls → 断言 steps 到上限后 kind='limit' 事件出现且 answer 非空（收尾调用无 tools）
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      let calls = 0;
      const kinds: string[] = [];
      const r = await AgentLoop.run(new AppConfig(), ctx, [], '继续搜', null,
        (e: LoopEvent) => { kinds.push(e.kind); },
        async (_messages: ChatMsg[], tools: ToolDef[] | null): Promise<OpenAiMessage> => {
          calls++;
          if (tools === null) {
            const fin: OpenAiMessage = { content: '步数上限收尾回答' };
            return fin;
          }
          const tc: ToolCall = { id: 'call_x' + calls, type: 'function', function: { name: 'read_web', arguments: '{"url":"http://127.0.0.1:1/x"}' } };
          const m: OpenAiMessage = { content: '', tool_calls: [tc] };
          return m;
        });
      expect(r.answer === '步数上限收尾回答').assertTrue();
      expect(kinds.indexOf('limit') >= 0).assertTrue();
      expect(r.steps === 11).assertTrue();
    });
    it('loop_cancel_throws_and_stops_fake', 0, async () => {
      // fake 第1轮回 tool_calls；fake 被调过 1 次后 isCancelled 返回 true
      // 断言：run reject 且 Error 为 AGENT_LOOP_CANCELLED，fake 不再被调用（引擎层中断）
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      let fakeCalls = 0;
      const tc: ToolCall = { id: 'call_c', type: 'function', function: { name: 'read_web', arguments: '{"url":"http://127.0.0.1:1/x"}' } };
      let threw = false;
      let cancelErrMsg = '';
      try {
        await AgentLoop.run(new AppConfig(), ctx, [], '取消我', null,
          (_e: LoopEvent) => {},
          async (_messages: ChatMsg[], _tools: ToolDef[] | null): Promise<OpenAiMessage> => {
            fakeCalls++;
            const m: OpenAiMessage = { content: '', tool_calls: [tc] };
            return m;
          },
          (): boolean => { return fakeCalls >= 1; });
      } catch (e) {
        threw = true;
        cancelErrMsg = (e as Error).message;
      }
      expect(threw).assertTrue();
      expect(cancelErrMsg === 'AGENT_LOOP_CANCELLED').assertTrue();
      expect(fakeCalls === 1).assertTrue();
    });
    it('loop_cancel_during_last_exec_skips_wrapup_call', 0, async () => {
      // execTool 期间取消（tool_done 计满 10 后旗标置真）→ limit 收尾 doChat 前的 checkCancel 命中，
      // 断言 run reject 且 fake 只被调 10 次（收尾不发起新 LLM 调用）
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      let fakeCalls = 0;
      let toolDoneCount = 0;
      const tc: ToolCall = { id: 'call_w', type: 'function', function: { name: 'read_web', arguments: '{"url":"http://127.0.0.1:1/x"}' } };
      let threw = false;
      try {
        await AgentLoop.run(new AppConfig(), ctx, [], '执行中取消', null,
          (e: LoopEvent) => { if (e.kind === 'tool_done') { toolDoneCount++; } },
          async (_messages: ChatMsg[], _tools: ToolDef[] | null): Promise<OpenAiMessage> => {
            fakeCalls++;
            const m: OpenAiMessage = { content: '', tool_calls: [tc] };
            return m;
          },
          (): boolean => { return toolDoneCount >= 10; });
      } catch (e) {
        threw = true;
        expect((e as Error).message === 'AGENT_LOOP_CANCELLED').assertTrue();
      }
      expect(threw).assertTrue();
      expect(fakeCalls === 10).assertTrue();
    });
    it('config_store_provider_fallback_is_bing_when_unset', 0, async () => {
      // 门2 bug 复现：searchProvider 键缺失时 load fallback 必须对齐 AppConfig 默认 'bing'
      // （开箱免 key 可搜）；曾落 'bocha' 会让 web_search 无 key 直接返回引导 error，开箱不可搜。
      // 只删该键不清整个 preferences：走精确 fallback 路径，不动其他已存配置。
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      const prefs = await preferences.getPreferences(ctx, 'appConfig');
      await prefs.delete('searchProvider');
      // ConfigStore 签名收 UIAbilityContext（preferences 层实际只吃 Context），测试侧显式收窄
      const c = await ConfigStore.load(ctx as object as common.UIAbilityContext);
      expect(c.searchProvider === 'bing').assertTrue();
    });
    it('pick_model_routes_longform_after_use_skill', 0, () => {
      // 双模型分工：默认主模型；use_skill 激活长文形态后切 longModel
      // （端点守卫：智谱端点才切，baseUrl 需显式给智谱地址）
      const c = new AppConfig();
      c.baseUrl = 'https://open.bigmodel.cn/api/paas/v4';
      c.model = 'glm-5.3-flash';
      c.longModel = 'glm-4.7';
      expect(AgentLoop.pickModel(c, false) === 'glm-5.3-flash').assertTrue();
      expect(AgentLoop.pickModel(c, true) === 'glm-4.7').assertTrue();
    });
    it('pick_model_falls_back_when_longmodel_empty', 0, () => {
      // longModel 空串（未配置/旧档无键）→ 即使长文激活也回退主模型，绝不发空 model 请求
      const c = new AppConfig();
      c.model = 'glm-5.3-flash';
      c.longModel = '';
      expect(AgentLoop.pickModel(c, true) === 'glm-5.3-flash').assertTrue();
      expect(AgentLoop.pickModel(c, false) === 'glm-5.3-flash').assertTrue();
    });
    it('pick_model_non_zhipu_endpoint_falls_back', 0, () => {
      // 端点兼容守卫：非智谱系端点（如 DeepSeek）不切 longModel（默认值是智谱模型名，
      // 切过去会 404 整轮失败）→ 即使长文激活也回退主模型
      const c = new AppConfig();
      c.baseUrl = 'https://api.deepseek.com/v1';
      c.model = 'deepseek-chat';
      c.longModel = 'glm-4.7';
      expect(AgentLoop.pickModel(c, true) === 'deepseek-chat').assertTrue();
      expect(AgentLoop.pickModel(c, false) === 'deepseek-chat').assertTrue();
    });
    it('loop_autoroute_off_skips_skill_listing', 0, async () => {
      // autoRoute=false 且无 @指定：system prompt 不注入 [可用技能清单]，只留手动触发守则
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      const cfg = new AppConfig();
      cfg.autoRoute = false;
      let sysPrompt = '';
      const r = await AgentLoop.run(cfg, ctx, [], '你好', null,
        (_e: LoopEvent) => {},
        async (messages: ChatMsg[], _tools: ToolDef[] | null): Promise<OpenAiMessage> => {
          if (sysPrompt === '') { sysPrompt = messages[0].content; }
          const m: OpenAiMessage = { content: '好的' };
          return m;
        });
      expect(r.answer === '好的').assertTrue();
      expect(sysPrompt.includes('[可用技能清单]')).assertFalse();
      expect(sysPrompt.includes('@技能名')).assertTrue();
    });
    it('loop_forced_skill_survives_autoroute_off', 0, async () => {
      // @手动指定不受 autoRoute=false 影响：技能块照常注入
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      const cfg = new AppConfig();
      cfg.autoRoute = false;
      const forced = new AgentSkill();
      forced.name = 'eli5';
      forced.description = '科普讲解';
      forced.content = '说明书正文';
      let sysPrompt = '';
      const r = await AgentLoop.run(cfg, ctx, [], '讲黑洞', forced,
        (_e: LoopEvent) => {},
        async (messages: ChatMsg[], _tools: ToolDef[] | null): Promise<OpenAiMessage> => {
          if (sysPrompt === '') { sysPrompt = messages[0].content; }
          const m: OpenAiMessage = { content: '讲解' };
          return m;
        });
      expect(r.answer === '讲解').assertTrue();
      expect(sysPrompt.includes('[用户已指定技能]')).assertTrue();
      expect(sysPrompt.includes('eli5')).assertTrue();
    });

    // ===== 一期图片链路（原 Attach.test 并入本 suite：aa test -s class logicTest 只跑同名 describe）=====
    it('attach_wire_messages_multimodal_content_array', 0, () => {
      const parts: ContentPart[] = [
        { type: 'text', text: '这是什么' },
        { type: 'image_url', image_url: { url: 'data:image/jpeg;base64,AAAA' } },
      ];
      const msg: ChatMsg = { role: 'user', content: '这是什么', content_parts: parts };
      const wire = LlmClient.toWireMessages([msg]);
      const body: string = JSON.stringify({ model: 'm', messages: wire });
      const parsed = JSON.parse(body) as Record<string, Object>;
      const msgs = parsed['messages'] as Object[];
      const content = (msgs[0] as Record<string, Object>)['content'];
      expect(Array.isArray(content)).assertTrue();
      const arr = content as Object[];
      expect(arr.length).assertEqual(2);
      expect((arr[0] as Record<string, Object>)['type']).assertEqual('text');
      expect((arr[1] as Record<string, Object>)['type']).assertEqual('image_url');
      expect(body.indexOf('content_parts') === -1).assertTrue();
    });

    it('attach_wire_messages_plain_text_stays_string', 0, () => {
      const msg: ChatMsg = { role: 'user', content: '你好' };
      const body: string = JSON.stringify({ messages: LlmClient.toWireMessages([msg]) });
      const parsed = JSON.parse(body) as Record<string, Object>;
      const content = ((parsed['messages'] as Object[])[0] as Record<string, Object>)['content'];
      expect(typeof content === 'string').assertTrue();
      expect(content as string).assertEqual('你好');
    });

    it('attach_compress_image_to_jpeg_base64', 0, async () => {
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext();
      const raw = await ctx.resourceManager.getRawFileContent('vis_test.png');
      const path: string = ctx.filesDir + '/attach_test.png';
      const f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, raw.buffer as ArrayBuffer);
      fs.closeSync(f);
      const b64: string = await AttachUtil.compressToB64(fileUri.getUriFromPath(path));
      expect(b64.length > 100).assertTrue();
      expect(b64.substring(0, 4)).assertEqual('/9j/');
      fs.unlinkSync(path);
    });

    it('attach_read_text_file_truncates_over_30k', 0, () => {
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext();
      const path: string = ctx.filesDir + '/attach_test.txt';
      const big: string = 'a'.repeat(31000);
      const f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, big);
      fs.closeSync(f);
      const text: string = AttachUtil.readTextFile(fileUri.getUriFromPath(path));
      expect(text.length < big.length).assertTrue();
      expect(text.indexOf('已截断') >= 0).assertTrue();
      fs.unlinkSync(path);
    });

    // ===== 二期文档抽取（自造 zip：zlib.compressFile 打目录，与真实 docx/xlsx 结构同构）=====
    it('attach_extract_docx_paragraphs', 0, async () => {
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext();
      const dir: string = ctx.filesDir + '/t_docx';
      if (fs.accessSync(dir)) { fs.rmdirSync(dir); }   // 上轮失败可能残留
      if (fs.accessSync(dir + '.zip')) { fs.unlinkSync(dir + '.zip'); }
      fs.mkdirSync(dir + '/word', true);
      const xml: string = '<?xml version="1.0"?><w:document><w:body>'
        + '<w:p><w:r><w:t>第一段：黑洞</w:t></w:r></w:p>'
        + '<w:p><w:r><w:t>第二段 &amp; 事件视界 &lt;测试&gt;</w:t></w:r></w:p>'
        + '</w:body></w:document>';
      const f = fs.openSync(dir + '/word/document.xml', fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, xml);
      fs.closeSync(f);
      const zip: string = ctx.filesDir + '/t_docx.zip';
      const copt: zlib.Options = { level: zlib.CompressLevel.COMPRESS_LEVEL_DEFAULT_COMPRESSION };
      await zlib.compressFile(dir, zip, copt);
      const text: string = await DocTextExtractor.extract(ctx, '测试.docx', fileUri.getUriFromPath(zip));
      expect(text.includes('第一段：黑洞')).assertTrue();
      expect(text.includes('第二段 & 事件视界 <测试>')).assertTrue();
      expect(text.split('\n').length).assertEqual(2);
      fs.unlinkSync(zip);
      fs.rmdirSync(dir);
    });

    it('attach_extract_xlsx_shared_strings', 0, async () => {
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext();
      const dir: string = ctx.filesDir + '/t_xlsx';
      if (fs.accessSync(dir)) { fs.rmdirSync(dir); }
      if (fs.accessSync(dir + '.zip')) { fs.unlinkSync(dir + '.zip'); }
      fs.mkdirSync(dir + '/xl/worksheets', true);
      const ss: string = '<?xml version="1.0"?><sst>'
        + '<si><t>姓名</t></si><si><t>张三</t></si><si><t>城市</t></si><si><t>成都</t></si></sst>';
      const sheet: string = '<?xml version="1.0"?><worksheet><sheetData>'
        + '<row><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>'
        + '<row><c r="A2" t="s"><v>2</v></c><c r="B2" t="s"><v>3</v></c></row>'
        + '</sheetData></worksheet>';
      let f = fs.openSync(dir + '/xl/sharedStrings.xml', fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, ss); fs.closeSync(f);
      f = fs.openSync(dir + '/xl/worksheets/sheet1.xml', fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, sheet); fs.closeSync(f);
      const zip: string = ctx.filesDir + '/t_xlsx.zip';
      const copt: zlib.Options = { level: zlib.CompressLevel.COMPRESS_LEVEL_DEFAULT_COMPRESSION };
      await zlib.compressFile(dir, zip, copt);
      const text: string = await DocTextExtractor.extract(ctx, '表格.xlsx', fileUri.getUriFromPath(zip));
      expect(text.includes('姓名	张三')).assertTrue();
      expect(text.includes('城市	成都')).assertTrue();
      expect(text.includes('工作表1')).assertTrue();
      fs.unlinkSync(zip);
      fs.rmdirSync(dir);
    });

    // ===== M5a 记忆引擎：BM25 / 隐私闸门 / 迁移 / 摘要（协议向量回放 + Kotlin 测试同构移植）=====

    it('memory_bm25_vectors_replay', 0, async () => {
      // 18 个向量用例逐 case 回放：id 序列必须完全一致，score 差 ≤1e-4（协议 §8.5）
      const root = await readVectorJson('bm25_cases.json');
      const corpusJson = root['corpus'] as Object[];
      const corpus: Doc[] = [];
      for (const d of corpusJson) {
        const rec = d as Record<string, Object>;
        const doc: Doc = { id: rec['id'] as number, content: rec['content'] as string, createdAt: rec['created_at'] as number };
        corpus.push(doc);
      }
      const cases = root['cases'] as Object[];
      for (const c of cases) {
        const cc = c as Record<string, Object>;
        const name = cc['name'] as string;
        const hits = Bm25.search(corpus, cc['query'] as string, cc['top_k'] as number, cc['now_ms'] as number);
        const expected = cc['expected'] as Object[];
        expect(`${name}#${hits.length}`).assertEqual(`${name}#${expected.length}`);
        for (let i = 0; i < expected.length; i++) {
          const e = expected[i] as Record<string, Object>;
          const expId = e['id'] as number;
          const expScore = e['score'] as number;
          expect(`${name}#${i}id=${hits[i].id}`).assertEqual(`${name}#${i}id=${expId}`);
          expect(Math.abs(hits[i].score - expScore) <= 1e-4).assertTrue();
        }
      }
    });

    it('memory_bm25_tokenize_rules', 0, () => {
      // §8.1 切词：ASCII 小写整段 / CJK 二元滑窗 / 段长1单字 / 其余分隔不产出
      expect(Bm25.tokenize('API2024').join(',')).assertEqual('api2024');
      expect(Bm25.tokenize('用户的猫').join('|')).assertEqual('用户|户的|的猫');
      expect(Bm25.tokenize('猫').join('|')).assertEqual('猫');
      expect(Bm25.tokenize('a，b!!c').join('|')).assertEqual('a|b|c');
      expect(Bm25.tokenize('？！').length).assertEqual(0);
      expect(Bm25.tokenize('').length).assertEqual(0);
    });

    it('memory_bm25_round4_unit', 0, () => {
      // 4 位小数舍入（HALF_UP 口径；容差 1e-4 下不依赖 .xxxx5 边界）
      expect(Bm25.round4(3.12058)).assertEqual(3.1206);
      expect(Bm25.round4(1.5024)).assertEqual(1.5024);
      expect(Bm25.round4(0.15)).assertEqual(0.15);
      expect(Bm25.round4(0)).assertEqual(0);
    });

    it('memory_privacy_vectors_detect_replay', 0, async () => {
      // 38 个向量用例逐 case 回放：detect 命中与顺序必须与 expected_hits 一致（secret→idnum→bankcard）
      const root = await readVectorJson('privacy_cases.json');
      const cases = root['cases'] as Object[];
      for (const c of cases) {
        const cc = c as Record<string, Object>;
        const name = cc['name'] as string;
        const hits = PrivacyGate.detect(cc['text'] as string);
        const expected = cc['expected_hits'] as Object[];
        let got = '';
        let exp = '';
        for (const h of hits) { got += `${h},`; }
        for (const h of expected) { exp += `${h as string},`; }
        expect(`${name}=${got}`).assertEqual(`${name}=${exp}`);
      }
    });

    it('memory_privacy_gear_matrix_replay', 0, async () => {
      // 挡位矩阵回放（§5.2）：单类样本文本 × 三挡，期望由 vectors gear_matrix 驱动
      const root = await readVectorJson('privacy_cases.json');
      const matrix = root['gear_matrix'] as Record<string, Object>;
      const samples: Record<string, string> = {
        'secret': '我的密码是abc123',
        'idnum': '身份证号是11010119900307775X',
        'bankcard': '还款卡号6222020000000000000，别忘了',
      };
      const gears: string[] = ['strict', 'standard', 'free'];
      const cats: string[] = ['secret', 'idnum', 'bankcard'];
      for (const gear of gears) {
        const row = matrix[gear] as Record<string, Object>;
        for (const cat of cats) {
          const expectReject: boolean = (row[cat] as string) === 'reject';
          const got = PrivacyGate.check(samples[cat], gear);
          expect(`${gear}/${cat}=${got ?? 'allow'}`).assertEqual(`${gear}/${cat}=${expectReject ? cat : 'allow'}`);
        }
      }
    });

    it('memory_privacy_check_first_rejected_category', 0, () => {
      // 多类命中时 check 上报首个被拒类别（§5.1 钉死：按 secret→idnum→bankcard 固定顺序）
      const text = '密码：Root@2024，证件11010119900307775X，卡号6222020000000000000';
      expect(PrivacyGate.check(text, 'strict')).assertEqual('secret');
      expect(PrivacyGate.check(text, 'standard')).assertEqual('idnum');
      expect(PrivacyGate.check(text, 'free') === null).assertTrue();
    });

    it('memory_privacy_gear_unknown_falls_back_strict', 0, () => {
      // 未知挡位字符串回退 strict（协议 §2 PRIVACY_GEAR_DEFAULT）
      expect(PrivacyGate.check('我的密码是abc123', 'weird')).assertEqual('secret');
      expect(PrivacyGate.check('还款卡号6222020000000000000，别忘了', '')).assertEqual('bankcard');
    });

    it('memory_privacy_luhn_unit_checks', 0, () => {
      // Luhn 单元：三张向量正例过、尾号 +1 死号不过、15 位/空/非数字不过
      expect(PrivacyGate.luhnValid('6222020000000000000')).assertTrue();
      expect(PrivacyGate.luhnValid('6222020000000007')).assertTrue();
      expect(PrivacyGate.luhnValid('621700000000000006')).assertTrue();
      expect(PrivacyGate.luhnValid('6222020000000000001')).assertFalse();
      expect(PrivacyGate.luhnValid('123456789012345')).assertFalse();
      expect(PrivacyGate.luhnValid('')).assertFalse();
      expect(PrivacyGate.luhnValid('1234abc')).assertFalse();
    });

    it('memory_migrate_vectors_replay', 0, async () => {
      // 10 个向量用例逐 case 回放：迁移行数与 content/type/source 全字段一致
      const root = await readVectorJson('migrate_cases.json');
      const cases = root['cases'] as Object[];
      for (const c of cases) {
        const cc = c as Record<string, Object>;
        const name = cc['name'] as string;
        const rows = Migrator.migrate(cc['input_json'] as string);
        const expected = cc['expected_rows'] as Object[];
        expect(`${name}#${rows.length}`).assertEqual(`${name}#${expected.length}`);
        for (let i = 0; i < expected.length; i++) {
          const e = expected[i] as Record<string, Object>;
          const r = rows[i];
          expect(`${name}#${i}=${r.content}`).assertEqual(`${name}#${i}=${e['content'] as string}`);
          expect(`${name}#${i}t=${r.type}`).assertEqual(`${name}#${i}t=${e['type'] as string}`);
          expect(`${name}#${i}s=${r.source}`).assertEqual(`${name}#${i}s=${e['source'] as string}`);
        }
      }
    });

    it('memory_constants_pinned', 0, () => {
      // 协议 §2/§8 常量钉死（双端写死，禁"约"）
      expect(Bm25.K1).assertEqual(1.2);
      expect(Bm25.B).assertEqual(0.75);
      expect(Bm25.RECENCY_WEIGHT).assertEqual(0.1);
      expect(Summarizer.SUMMARY_TURN_THRESHOLD).assertEqual(12);
      expect(Summarizer.SUMMARY_CHAR_THRESHOLD).assertEqual(16000);
      expect(Summarizer.SUMMARY_MAX_CHARS).assertEqual(300);
      expect(Migrator.TYPE_FACT).assertEqual('fact');
      expect(Migrator.SOURCE_LEGACY_M2).assertEqual('legacy-m2');
    });

    it('memory_summarizer_window_zero_until_turn_returns_all', 0, () => {
      // [S2] untilTurn=0 → 全量窗口
      const rows = mkRows(3);
      const w = Summarizer.windowRows(rows, 0);
      expect(w.length).assertEqual(rows.length);
      expect(w[0].content === rows[0].content && w[w.length - 1].content === rows[rows.length - 1].content).assertTrue();
    });

    it('memory_summarizer_window_cuts_after_boundary_assistant_row', 0, () => {
      // [S2] 边界=turn_no≤untilTurn 的最后一条 assistant 行，窗口为其后全部行（含下一轮 user 行）
      const rows = mkRows(5);
      const w = Summarizer.windowRows(rows, 3);
      expect(w.length).assertEqual(4);
      expect(w[0].content).assertEqual('问4');
      expect(w[w.length - 1].content).assertEqual('答5');
    });

    it('memory_summarizer_window_until_turn_beyond_max_returns_empty', 0, () => {
      // [S2] 边界 turn_no 大于现有最大轮次 → 窗口为空（历史只剩摘要）
      const rows = mkRows(3);
      expect(Summarizer.windowRows(rows, 99).length).assertEqual(0);
    });

    it('memory_summarizer_trigger_turn_threshold', 0, () => {
      // [S1] 阈值钉死：12 轮不触发、13 轮触发（assistant 行数口径）
      expect(Summarizer.shouldTrigger(mkRows(12))).assertFalse();
      expect(Summarizer.shouldTrigger(mkRows(13))).assertTrue();
    });

    it('memory_summarizer_trigger_char_threshold', 0, () => {
      // [S1] 字符超阈值触发：短轮次但总量大（5*(2*(4+1+2000))=20050 > 16000）
      const rows: Msg[] = [];
      const big: string = 'x'.repeat(2000);
      for (let i = 1; i <= 5; i++) {
        const u = new Msg();
        u.id = rows.length + 1; u.convId = 1; u.role = 'user'; u.content = big;
        u.plain = ''; u.turnNo = 0; u.createdAt = i * 10;
        rows.push(u);
        const a = new Msg();
        a.id = rows.length + 1; a.convId = 1; a.role = 'assistant'; a.content = big;
        a.plain = big; a.turnNo = i; a.createdAt = i * 10 + 1;
        rows.push(a);
      }
      expect(Summarizer.shouldTrigger(rows)).assertTrue();
    });

    it('memory_summarizer_span_oldest_half_round_up', 0, () => {
      // [S3] 跨度切分：13 轮取 7 轮（向上取整），跨度含第 7 条 assistant 行及其前导 user 行
      const window = mkRows(13);
      const span: Span = Summarizer.spanToSummarize(window)!;
      expect(span.rows.length).assertEqual(14);  // 7 轮 × (user+assistant)
      expect(span.endTurnNo).assertEqual(7);
      expect(span.rows[span.rows.length - 1].content).assertEqual('答7');
    });

    it('memory_summarizer_span_too_few_turns_returns_null', 0, () => {
      // [S3] 不足 2 轮返回 null（防御）
      expect(Summarizer.spanToSummarize(mkRows(1)) === null).assertTrue();
    });

    it('memory_summarizer_prompt_uses_plain_for_assistant', 0, () => {
      // [S4] 提示词构造：user 原文、assistant 优先 plain（防大 HTML 撑爆摘要请求）
      const u = new Msg();
      u.id = 1; u.convId = 1; u.role = 'user'; u.content = '<p>问题</p>'; u.plain = ''; u.turnNo = 0; u.createdAt = 1;
      const a = new Msg();
      a.id = 2; a.convId = 1; a.role = 'assistant'; a.content = '<html>大页面</html>'; a.plain = '纯文本回答'; a.turnNo = 1; a.createdAt = 2;
      const p = Summarizer.buildPrompt([u, a]);
      expect(p.includes('用户：<p>问题</p>')).assertTrue();
      expect(p.includes('助手：纯文本回答')).assertTrue();
      expect(p.includes('<html>大页面</html>')).assertFalse();
    });

    it('memory_summarizer_join_summary', 0, () => {
      // [S5] 摘要拼接：旧空直用、非空 \n 连接
      expect(Summarizer.joinSummary('', '新摘要')).assertEqual('新摘要');
      expect(Summarizer.joinSummary('旧', '新')).assertEqual('旧\n新');
    });

    // ===== M3 续跑轨迹落库瘦身（stripTraceForPersist）+ 工具输出软预算（applyToolBudget）=====

    it('strip_trace_replaces_image_parts_with_placeholder', 0, () => {
      // 含图轮：content_parts（含 base64 大图）整体替换为单条占位文本；原 trace 不被改写（深拷贝）
      const big: string = 'A'.repeat(1000);
      const u: ChatMsg = {
        role: 'user', content: '这是什么', content_parts: [
          { type: 'text', text: '这是什么' },
          { type: 'image_url', image_url: { url: 'data:image/jpeg;base64,' + big } },
        ]
      };
      const a: ChatMsg = { role: 'assistant', content: '答' };
      const src: ChatMsg[] = [u, a];
      const out = stripTraceForPersist(src);
      expect(out.length).assertEqual(2);
      expect(out[0].content_parts !== undefined && out[0].content_parts!.length === 1).assertTrue();
      expect(out[0].content_parts !== undefined && out[0].content_parts![0].type === 'text').assertTrue();
      expect(out[0].content_parts !== undefined &&
        out[0].content_parts![0].text === '[图片/附件内容已于首轮消费，续跑上下文省略]').assertTrue();
      expect(JSON.stringify(out).includes(big)).assertFalse();
      expect(JSON.stringify(out).includes('[图片/附件内容已于首轮消费，续跑上下文省略]')).assertTrue();
      // 深拷贝语义：入参 trace 的原 content_parts 保持不变
      expect(u.content_parts !== undefined && u.content_parts!.length === 2).assertTrue();
      expect(out[1] === a).assertTrue();
    });

    it('strip_trace_passthrough_when_no_parts', 0, () => {
      // 无图轮原样：无 content_parts（含空数组）→ 同实例返回，消息身份不变
      const u: ChatMsg = { role: 'user', content: '你好' };
      const a: ChatMsg = { role: 'assistant', content: '答', content_parts: [] };
      const src: ChatMsg[] = [u, a];
      const out = stripTraceForPersist(src);
      expect(out === src).assertTrue();
      expect(out[0] === u && out[1] === a).assertTrue();
    });

    it('tool_budget_within_returns_untouched', 0, () => {
      // 未超限：原样返回
      expect(AgentLoop.applyToolBudget('abc', 0) === 'abc').assertTrue();
      expect(AgentLoop.applyToolBudget('x'.repeat(29999), 0) === 'x'.repeat(29999)).assertTrue();
    });

    it('tool_budget_partial_truncates_with_marker', 0, () => {
      // keep>0：截断到预算余量（30000-15000=15000）并追加截断标记
      const out = AgentLoop.applyToolBudget('a'.repeat(20000), 15000);
      const marker: string = '\n…[已截断：本轮工具输出累计超 30000 字软预算]';
      expect(out.length).assertEqual(15000 + marker.length);
      expect(out.substring(0, 15000) === 'a'.repeat(15000)).assertTrue();
      expect(out.endsWith(marker)).assertTrue();
    });

    it('tool_budget_exhausted_returns_fixed_hint', 0, () => {
      // keep==0：预算已被前序工具耗尽 → 固定短文案（不再 substring(0,0) 空截断+标记）
      const out = AgentLoop.applyToolBudget('a'.repeat(100), 30000);
      expect(out === '本轮工具输出预算已耗尽，请基于已有结果作答').assertTrue();
      expect(out.includes('已截断')).assertFalse();
    });

    it('screen_guard_keyword_hits', 0, () => {
      // 关键词命中即敏感（与 Android ScreenGuard 同表）
      expect(ScreenGuard.hasSensitive('请输入支付密码')).assertTrue();
      expect(ScreenGuard.hasSensitive('向对方转账 100 元')).assertTrue();
      expect(ScreenGuard.hasSensitive('短信验证码')).assertTrue();
      expect(ScreenGuard.hasSensitive('今天天气不错')).assertFalse();
      expect(ScreenGuard.hasSensitive('')).assertFalse();
    });

    it('screen_blacklist_default_and_user', 0, () => {
      // 内置鸿蒙 bundle 命中；用户增补集命中；未命中放行；空 bundle 永不放行
      expect(ScreenBlacklist.isBlocked('com.alipay.alipayfinance', new Set<string>())).assertTrue();
      expect(ScreenBlacklist.isBlocked('com.example.unknown', new Set<string>(['com.example.unknown']))).assertTrue();
      expect(ScreenBlacklist.isBlocked('com.huawei.hmos.settings', new Set<string>())).assertFalse();
      expect(ScreenBlacklist.isBlocked('', new Set<string>(['com.example.x']))).assertFalse();
    });

    it('screen_format_matches_android_schema', 0, () => {
      // 输出格式与 Android ScreenFormat.format 逐段一致（双端模型提示词通用）
      const nodes: ScreenNode[] = [
        new ScreenNode('WLAN', 'Text', 660, 400, false),
        new ScreenNode('', 'Image', 660, 500, true),
      ];
      const out = ScreenFormat.format('com.huawei.hmos.settings', nodes);
      expect(out.startsWith('当前应用: com.huawei.hmos.settings（可读文本节点 1 个）')).assertTrue();
      expect(out.includes('[1] Text "WLAN" @(660,400)')).assertTrue();
      expect(out.includes('[2] Image "" @(660,500) 可点击')).assertTrue();
      // 密码框只出标记不出值（isPassword 置位时；鸿蒙当前无识别来源，格式路径仍须正确）
      const pwNode = new ScreenNode('secret', 'TextInput', 1, 2, true);
      pwNode.isPassword = true;
      const pw = ScreenFormat.format('x', [pwNode]);
      expect(pw.includes('[密码框]')).assertTrue();
      expect(pw.includes('secret')).assertFalse();
    });
  });
}
