// 搜索路由：builtin 强制内置；external 有key走外部；auto 智谱系走内置，其余有key走外部，无key NONE
import { AppConfig, NetMode } from '../model/Types';

export class SearchRouter {
  static resolve(cfg: AppConfig): NetMode {
    if (cfg.searchMode === 'builtin') { return NetMode.BUILTIN; }
    if (cfg.searchMode === 'external') {
      return cfg.searchApiKey !== '' ? NetMode.EXTERNAL : NetMode.NONE;
    }
    // auto：智谱系 baseURL 用内置联网
    if (cfg.baseUrl.includes('bigmodel.cn')) { return NetMode.BUILTIN; }
    return cfg.searchApiKey !== '' ? NetMode.EXTERNAL : NetMode.NONE;
  }
}
