// 配置存取：preferences 'appConfig'，键与 AppConfig 字段同名
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { AppConfig } from '../model/Types';

export class ConfigStore {
  static async load(ctx: common.UIAbilityContext): Promise<AppConfig> {
    const p = await preferences.getPreferences(ctx, 'appConfig');
    const c = new AppConfig();
    c.baseUrl = (await p.get('baseUrl', '')) as string;
    c.apiKey = (await p.get('apiKey', '')) as string;
    c.model = (await p.get('model', '')) as string;
    c.longModel = (await p.get('longModel', 'glm-4.7')) as string;
    c.autoRoute = (await p.get('autoRoute', true)) as boolean;
    c.searchMode = (await p.get('searchMode', 'auto')) as string;
    c.searchProvider = (await p.get('searchProvider', 'bing')) as string;
    c.searchApiKey = (await p.get('searchApiKey', '')) as string;
    return c;
  }

  static async save(ctx: common.UIAbilityContext, cfg: AppConfig): Promise<void> {
    const p = await preferences.getPreferences(ctx, 'appConfig');
    await p.put('baseUrl', cfg.baseUrl);
    await p.put('apiKey', cfg.apiKey);
    await p.put('model', cfg.model);
    await p.put('longModel', cfg.longModel);
    await p.put('autoRoute', cfg.autoRoute);
    await p.put('searchMode', cfg.searchMode);
    await p.put('searchProvider', cfg.searchProvider);
    await p.put('searchApiKey', cfg.searchApiKey);
    await p.flush();
  }

  static async getWallpaper(ctx: common.UIAbilityContext): Promise<string> {
    const p = await preferences.getPreferences(ctx, 'appConfig');
    return (await p.get('wallpaper', '')) as string;
  }

  static async setWallpaper(ctx: common.UIAbilityContext, path: string): Promise<void> {
    const p = await preferences.getPreferences(ctx, 'appConfig');
    await p.put('wallpaper', path);
    await p.flush();
  }
}

