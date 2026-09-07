// 内置三工具：web_search / read_web / use_skill
// 统一契约：execute 失败一律返回 '{"error":"..."}' 字符串，不向上抛异常
import { http } from '@kit.NetworkKit';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { AppConfig, AgentSkill } from '../../model/Types';
import { SearchClient } from '../SearchClient';
import { HtmlExtractor } from '../HtmlExtractor';
import { SkillRepo } from '../../store/SkillRepo';
import { AgentTool } from './AgentTool';

// execute 返回值在失败路径上的统一形态
interface ToolError { error: string }

// Chrome UA：read_web 与必应爬取共用同一伪装，避免被目标站按 UA 拦截
const CHROME_UA: string = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';

export class BuiltinTools {
  // 三个工具的工厂；skillForced 非 null（用户 @手动指定技能）时 use_skill 免查库直接返回该技能
  static createAll(ctx: common.Context, cfg: AppConfig, skillForced: AgentSkill | null): AgentTool[] {
    const out: AgentTool[] = [];
    out.push(BuiltinTools.buildWebSearch(cfg));
    out.push(BuiltinTools.buildReadWeb());
    out.push(BuiltinTools.buildUseSkill(ctx, skillForced));
    return out;
  }

  // 失败收敛 helper：统一走 JSON.stringify 保证 error 字段名与 JSON 合法性
  static err(msg: string): string {
    const e: ToolError = { error: msg };
    return JSON.stringify(e);
  }

  // 取 BusinessError 的 message（普通 Error 强转后同样取得到 message）
  static errMsg(e: BusinessError): string {
    return e.message !== undefined && e.message !== '' ? e.message : String(e);
  }

  // args 解析 + 必填字段校验：返回空串表示缺参（由调用方转 error）
  static requireField(obj: object, field: string): string {
    const dict = obj as Record<string, Object>;
    const v = dict[field];
    if (typeof v === 'string' && v !== '') {
      return v as string;
    }
    return '';
  }

  // web_search：{query} → SearchClient.search（必应轨已带关键词提取）→ 前 5 条拼
  // `[1] 标题\n摘要\n链接` 文本
  private static buildWebSearch(cfg: AppConfig): AgentTool {
    const tool = new AgentTool();
    tool.name = 'web_search';
    tool.description = '联网搜索。输入关键词或自然语言问题，返回前5条搜索结果（标题、摘要、链接）。需要时效性信息或自身知识不确定时使用。';
    tool.parametersJson = '{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词或自然语言问题"}},"required":["query"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      try {
        const query: string = BuiltinTools.requireField(JSON.parse(argsJson), 'query');
        if (query === '') {
          return BuiltinTools.err('web_search 缺少 query 参数');
        }
        const hits = await new SearchClient(cfg).search(query);
        if (hits.length === 0) {
          return '未搜索到相关结果，请换关键词重试或依靠自身知识回答。';
        }
        const lines: string[] = [];
        for (let i = 0; i < hits.length && i < 5; i++) {
          lines.push(`[${i + 1}] ${hits[i].title}\n${hits[i].snippet}\n${hits[i].url}`);
        }
        return lines.join('\n');
      } catch (e) {
        return BuiltinTools.err(`web_search 失败: ${BuiltinTools.errMsg(e as BusinessError)}`);
      }
    };
    return tool;
  }

  // read_web：{url} → http GET（Chrome UA、20s 超时）→ stripTags 截 6000 字；非 2xx/异常 → error
  private static buildReadWeb(): AgentTool {
    const tool = new AgentTool();
    tool.name = 'read_web';
    tool.description = '读取网页正文。输入完整 URL，返回剥掉标签后的纯文本（最长6000字）。搜索结果需要展开细节时使用。';
    tool.parametersJson = '{"type":"object","properties":{"url":{"type":"string","description":"要读取的网页完整URL"}},"required":["url"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      let url = '';
      try {
        url = BuiltinTools.requireField(JSON.parse(argsJson), 'url');
      } catch (e) {
        return BuiltinTools.err(`read_web 参数不是合法 JSON: ${BuiltinTools.errMsg(e as BusinessError)}`);
      }
      if (url === '') {
        return BuiltinTools.err('read_web 缺少 url 参数');
      }
      const hr = http.createHttp();
      try {
        const opt: http.HttpRequestOptions = {
          method: http.RequestMethod.GET,
          header: {
            'User-Agent': CHROME_UA,
            'Accept-Language': 'zh-CN,zh;q=0.9'
          },
          readTimeout: 20000,
          connectTimeout: 15000,
          expectDataType: http.HttpDataType.STRING
        };
        const resp = await hr.request(url, opt);
        if (resp.responseCode < 200 || resp.responseCode >= 300) {
          return BuiltinTools.err(`read_web HTTP ${resp.responseCode}: ${String(resp.result).substring(0, 300)}`);
        }
        let text: string = HtmlExtractor.stripTags(String(resp.result));
        if (text.length > 6000) {
          text = text.substring(0, 6000);
        }
        if (text === '') {
          return BuiltinTools.err('read_web 页面剥标签后为空，可能不是正文型网页');
        }
        return text;
      } catch (e) {
        return BuiltinTools.err(`read_web 失败: ${BuiltinTools.errMsg(e as BusinessError)}`);
      } finally {
        hr.destroy();
      }
    };
    return tool;
  }

  // use_skill：{name} → SkillRepo.getByName → 命中返回说明书全文让模型严格照做；未命中 → error。
  // skillForced 非 null 时免查库直接返回（@手动指定场景，且允许指向未入库的临时技能）
  private static buildUseSkill(ctx: common.Context, skillForced: AgentSkill | null): AgentTool {
    const tool = new AgentTool();
    tool.name = 'use_skill';
    tool.description = '调用技能配方。技能是写好的详细操作说明书，命中后你必须严格按说明书执行任务。';
    tool.parametersJson = '{"type":"object","properties":{"name":{"type":"string","description":"技能名称"}},"required":["name"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      try {
        if (skillForced !== null) {
          return BuiltinTools.skillPayload(skillForced.name, skillForced.content);
        }
        const name: string = BuiltinTools.requireField(JSON.parse(argsJson), 'name');
        if (name === '') {
          return BuiltinTools.err('use_skill 缺少 name 参数');
        }
        const skill = await SkillRepo.getByName(ctx, name);
        if (skill === null) {
          return BuiltinTools.err(`未找到名为「${name}」的技能`);
        }
        return BuiltinTools.skillPayload(skill.name, skill.content);
      } catch (e) {
        return BuiltinTools.err(`use_skill 失败: ${BuiltinTools.errMsg(e as BusinessError)}`);
      }
    };
    return tool;
  }

  static skillPayload(name: string, content: string): string {
    return `以下是「${name}」skill 的完整说明书，请严格按它执行：\n${content}`;
  }
}
