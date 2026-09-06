import { describe, it, expect } from '@ohos/hypium';
import { HtmlGuard } from '../../../main/ets/service/HtmlGuard';
import { HtmlExtractor } from '../../../main/ets/service/HtmlExtractor';
import { SearchRouter } from '../../../main/ets/service/SearchRouter';
import { AppConfig, SearchHit } from '../../../main/ets/model/Types';
import { PromptBuilder, HistoryTurn } from '../../../main/ets/service/PromptBuilder';

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
    it('router_auto_zhipu_builtin', 0, () => {
      const c = new AppConfig();
      c.baseUrl = 'https://open.bigmodel.cn/api/paas/v4';
      expect(SearchRouter.resolve(c) === 0).assertTrue();
    });
    it('router_auto_external_without_zhipu', 0, () => {
      const c = new AppConfig();
      c.baseUrl = 'https://api.deepseek.com/v1';
      c.searchApiKey = 'sk-x';
      expect(SearchRouter.resolve(c) === 1).assertTrue();
    });
    it('router_auto_none_without_key', 0, () => {
      const c = new AppConfig();
      c.baseUrl = 'https://api.deepseek.com/v1';
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
  });
}
