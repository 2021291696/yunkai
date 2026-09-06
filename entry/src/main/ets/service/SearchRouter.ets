// 搜索路由：builtin 强制内置；external 走外部；auto 智谱系走内置，其余走外部，不可用 NONE
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
    // auto：智谱系 baseURL 用内置联网
    if (cfg.baseUrl.includes('bigmodel.cn')) { return NetMode.BUILTIN; }
    return SearchRouter.externalAvailable(cfg) ? NetMode.EXTERNAL : NetMode.NONE;
  }
}
