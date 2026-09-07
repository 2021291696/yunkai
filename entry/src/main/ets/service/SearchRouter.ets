// 搜索路由：builtin 强制内置；external 走外部；auto 必应外部优先，不可用时智谱系回退内置
// （实测 glm-4.7 不执行 web_search 工具，glm-4.5 可用——内置联网只在用户明确选择时用）
// 外部轨道可用性 = 有搜索 key，或 provider=bing（爬取免 key）
import { AppConfig, NetMode } from '../model/Types';

export class SearchRouter {
  private static externalAvailable(cfg: AppConfig): boolean {
    return cfg.searchApiKey !== '' || cfg.searchProvider === 'bing';
  }

  static resolve(cfg: AppConfig): NetMode {
    if (cfg.searchMode === 'builtin') { return NetMode.BUILTIN; }
    if (cfg.searchMode === 'external') {
      return SearchRouter.externalAvailable(cfg) ? NetMode.EXTERNAL : NetMode.NONE;
    }
    // auto：必应外部优先（免费且全模型可用）；必应不可用时智谱系回退内置
    if (SearchRouter.externalAvailable(cfg)) { return NetMode.EXTERNAL; }
    if (cfg.baseUrl.includes('bigmodel.cn')) { return NetMode.BUILTIN; }
    return NetMode.NONE;
  }
}
