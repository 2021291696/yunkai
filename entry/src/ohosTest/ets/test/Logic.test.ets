import { describe, it, expect } from '@ohos/hypium';
import AbilityDelegatorRegistry from '@ohos.app.ability.abilityDelegatorRegistry';
import { HtmlGuard, ReplyKind } from '../../../main/ets/service/HtmlGuard';
import { HtmlExtractor } from '../../../main/ets/service/HtmlExtractor';
import { SearchRouter } from '../../../main/ets/service/SearchRouter';
import { AppConfig, ChatMsg, SearchHit, ToolCall, ToolDef } from '../../../main/ets/model/Types';
import { PromptBuilder, HistoryTurn } from '../../../main/ets/service/PromptBuilder';
import { LlmClient, OpenAiMessage } from '../../../main/ets/service/LlmClient';
import { AgentLoop, LoopEvent } from '../../../main/ets/service/AgentLoop';
import { SearchClient } from '../../../main/ets/service/SearchClient';
import { Db } from '../../../main/ets/store/Db';
import { SkillRepo } from '../../../main/ets/store/SkillRepo';
import { SkillImporter } from '../../../main/ets/service/SkillImporter';
import { AgentTool } from '../../../main/ets/service/tools/AgentTool';
import { BuiltinTools } from '../../../main/ets/service/tools/BuiltinTools';

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
    it('marker_detection', 0, () => {
      expect(HtmlGuard.detectReadMarker('[[READ_HTML:2]]') === 2).assertTrue();
      expect(HtmlGuard.detectReadMarker('<html>[[READ_HTML:2]]</html>') === null).assertTrue();
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
    it('prompt_contains_history_and_turn_no', 0, () => {
      const h = new HistoryTurn(); h.turnNo = 1; h.question = '什么是电'; h.plain = '电子流动';
      const p = PromptBuilder.buildUserPrompt([h], '为什么会触电', []);
      expect(p.includes('[第1轮]')).assertTrue();
      expect(p.includes('电子流动')).assertTrue();
      expect(p.includes('为什么会触电')).assertTrue();
    });
    it('prompt_contains_search_hits', 0, () => {
      const s: SearchHit = { title: '安全用电', url: 'https://a.b', snippet: '36V以下是安全电压' };
      const p = PromptBuilder.buildUserPrompt([], '安全电压是多少', [s]);
      expect(p.includes('36V以下是安全电压')).assertTrue();
      expect(p.includes('https://a.b')).assertTrue();
    });
    it('prompt_empty_history_no_block', 0, () => {
      const p = PromptBuilder.buildUserPrompt([], '什么是光', []);
      expect(p.includes('历史对话')).assertFalse();
    });
    it('read_retry_prompt_wraps_html', 0, () => {
      const p = PromptBuilder.buildReadRetryPrompt(2, '<html>x</html>', '再讲细点');
      expect(p.includes('第2轮完整HTML原文')).assertTrue();
      expect(p.includes('<html>x</html>')).assertTrue();
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
    });
    it('request_body_builtin_web_search_tooldef', 0, () => {
      const msgs: ChatMsg[] = [{ role: 'user', content: 'q' }];
      const body = LlmClient.buildRequestBody('glm-4.7', msgs, LlmClient.BUILTIN_WEB_SEARCH);
      expect(body.includes('"type":"web_search"')).assertTrue();
      expect(body.includes('"enable":true')).assertTrue();
    });
    it('extract_message_parses_tool_calls', 0, () => {
      const j = '{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\\"query\\":\\"AI\\"}"}}]}}]}';
      const m = LlmClient.extractMessage(j);
      expect(m.tool_calls !== undefined && m.tool_calls!.length === 1).assertTrue();
      expect((m.tool_calls as ToolCall[])[0].function.name === 'web_search').assertTrue();
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
    it('skill_repo_crud_and_seed', 0, async () => {
      // 注：getAppContext() 返回的模块级 Context 会被 getRdbStore 原生校验拒绝(401)，
      // 必须升到 ApplicationContext 才能建库。
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      await Db.get(ctx); // 建表
      await SkillRepo.seedIfEmpty(ctx);
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
      await Db.get(ctx); await SkillRepo.seedIfEmpty(ctx);
      const tools = BuiltinTools.createAll(ctx, new AppConfig(), null);
      let eli5Tool: AgentTool | null = null;
      for (const t of tools) { if (t.name === 'use_skill') { eli5Tool = t; } }
      const out = await eli5Tool!.execute('{"name":"eli5"}');
      expect(out.includes('章节')).assertTrue();
    });
    it('tool_error_becomes_error_json', 0, async () => {
      const ctx = AbilityDelegatorRegistry.getAbilityDelegator().getAppContext().getApplicationContext();
      const tools = BuiltinTools.createAll(ctx, new AppConfig(), null);
      let readWeb: AgentTool | null = null;
      for (const t of tools) { if (t.name === 'read_web') { readWeb = t; } }
      const out = await readWeb!.execute('{"url":"http://127.0.0.1:1/x"}');
      expect(out.includes('"error"')).assertTrue();
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
  });
}
