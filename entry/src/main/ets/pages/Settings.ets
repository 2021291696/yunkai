// 设置页：OpenAI 兼容三项配置 + 获取模型列表 + 联网方式三档 + 独立搜索配置
import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { AppConfig } from '../model/Types';
import { ConfigStore } from '../store/ConfigStore';
import { LlmClient } from '../service/LlmClient';

interface ModelSelectOption {
  value: string;
}

@Entry
@Component
struct Settings {
  @State baseUrl: string = '';
  @State apiKey: string = '';
  @State model: string = '';
  @State searchMode: string = 'auto';
  @State searchProvider: string = 'bocha';
  @State searchApiKey: string = '';
  @State modelOptions: ModelSelectOption[] = [];
  @State modelSelected: string = '';
  @State fetchingModels: boolean = false;

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  aboutToAppear(): void {
    this.loadConfig();
  }

  private async loadConfig(): Promise<void> {
    const cfg: AppConfig = await ConfigStore.load(this.ctx());
    this.baseUrl = cfg.baseUrl;
    this.apiKey = cfg.apiKey;
    this.model = cfg.model;
    this.searchMode = cfg.searchMode;
    this.searchProvider = cfg.searchProvider;
    this.searchApiKey = cfg.searchApiKey;
  }

  private collect(): AppConfig {
    const c = new AppConfig();
    c.baseUrl = this.baseUrl.trim();
    c.apiKey = this.apiKey.trim();
    c.model = this.model.trim();
    c.searchMode = this.searchMode;
    c.searchProvider = this.searchProvider;
    c.searchApiKey = this.searchApiKey.trim();
    return c;
  }

  private toast(msg: string): void {
    promptAction.showToast({ message: msg, duration: 2500 });
  }

  private async fetchModels(): Promise<void> {
    const cfg: AppConfig = this.collect();
    if (cfg.baseUrl === '' || cfg.apiKey === '') {
      this.toast('请先填写 API 地址和密钥');
      return;
    }
    this.fetchingModels = true;
    try {
      const llm = new LlmClient(cfg);
      const ids: string[] = await llm.listModels();
      if (ids.length === 0) {
        this.toast('模型列表为空');
        return;
      }
      const opts: ModelSelectOption[] = [];
      for (let i = 0; i < ids.length; i++) {
        opts.push({ value: ids[i] } as ModelSelectOption);
      }
      this.modelOptions = opts;
      this.toast(`获取到 ${ids.length} 个模型，请在下拉中选择`);
    } catch (e) {
      this.toast('获取失败：' + (e as Error).message);
    } finally {
      this.fetchingModels = false;
    }
  }

  private async save(): Promise<void> {
    const cfg: AppConfig = this.collect();
    if (cfg.baseUrl === '' || cfg.apiKey === '' || cfg.model === '') {
      this.toast('API 地址、密钥、模型名都不能为空');
      return;
    }
    await ConfigStore.save(this.ctx(), cfg);
    this.toast('已保存');
    router.back();
  }

  build() {
    Scroll() {
      Column({ space: 18 }) {
        Text('设置')
          .fontSize(26)
          .fontWeight(FontWeight.Bold)
          .width('100%')

        // ===== API 配置卡片 =====
        Column({ space: 12 }) {
          Text('API 配置').fontSize(16).fontColor('#7A7A7A').width('100%')
          Text('API 地址').fontSize(14).width('100%')
          TextInput({ text: this.baseUrl, placeholder: 'https://open.bigmodel.cn/api/paas/v4' })
            .type(InputType.Normal)
            .width('100%')
            .onChange((v: string) => { this.baseUrl = v; })
          Text('API 密钥').fontSize(14).width('100%')
          TextInput({ text: this.apiKey, placeholder: 'sk-...' })
            .type(InputType.Password)
            .width('100%')
            .onChange((v: string) => { this.apiKey = v; })
          Text('模型名').fontSize(14).width('100%')
          Row({ space: 10 }) {
            TextInput({ text: this.model, placeholder: '如 glm-4.7 / deepseek-chat' })
              .type(InputType.Normal)
              .layoutWeight(1)
              .onChange((v: string) => { this.model = v; })
            Button(this.fetchingModels ? '获取中…' : '获取模型列表')
              .enabled(!this.fetchingModels)
              .onClick(() => {
                this.fetchModels();
              })
          }
          .width('100%')

          if (this.modelOptions.length > 0) {
            Select(this.modelOptions as SelectOption[])
              .selected(this.modelSelected === '' ? -1 : 0)
              .value(this.modelSelected === '' ? '选择模型' : this.modelSelected)
              .width('100%')
              .onSelect((index: number) => {
                const picked: string = this.modelOptions[index].value;
                this.modelSelected = picked;
                this.model = picked;
              })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .padding(16)
        .borderRadius(16)
        .backgroundColor('#1C1C1E')
        .width('100%')

        // ===== 联网方式卡片 =====
        Column({ space: 12 }) {
          Text('联网搜索').fontSize(16).fontColor('#7A7A7A').width('100%')
          Text('联网方式').fontSize(14).width('100%')
          Row({ space: 18 }) {
            Radio({ value: 'auto', group: 'searchMode' })
              .checked(this.searchMode === 'auto')
              .onChange((on: boolean) => { if (on) { this.searchMode = 'auto'; } })
            Text('自动').fontSize(15)
            Radio({ value: 'builtin', group: 'searchMode' })
              .checked(this.searchMode === 'builtin')
              .onChange((on: boolean) => { if (on) { this.searchMode = 'builtin'; } })
            Text('仅内置').fontSize(15)
            Radio({ value: 'external', group: 'searchMode' })
              .checked(this.searchMode === 'external')
              .onChange((on: boolean) => { if (on) { this.searchMode = 'external'; } })
            Text('仅独立').fontSize(15)
          }
          .width('100%')

          Text('搜索源').fontSize(14).width('100%')
          Row({ space: 18 }) {
            Radio({ value: 'bocha', group: 'searchProvider' })
              .checked(this.searchProvider === 'bocha')
              .onChange((on: boolean) => { if (on) { this.searchProvider = 'bocha'; } })
            Text('博查').fontSize(15)
            Radio({ value: 'tavily', group: 'searchProvider' })
              .checked(this.searchProvider === 'tavily')
              .onChange((on: boolean) => { if (on) { this.searchProvider = 'tavily'; } })
            Text('Tavily').fontSize(15)
          }
          .width('100%')

          Text('搜索密钥').fontSize(14).width('100%')
          TextInput({ text: this.searchApiKey, placeholder: '博查或 Tavily 的 key' })
            .type(InputType.Password)
            .width('100%')
            .onChange((v: string) => { this.searchApiKey = v; })

          Text('自动=智谱系模型用内置联网，其他模型走独立搜索（需密钥）')
            .fontSize(12).fontColor('#8A8A8A').width('100%')
        }
        .alignItems(HorizontalAlign.Start)
        .padding(16)
        .borderRadius(16)
        .backgroundColor('#1C1C1E')
        .width('100%')

        Button('保存', { type: ButtonType.Capsule })
          .width('100%')
          .height(46)
          .fontSize(17)
          .onClick(() => {
            this.save();
          })
      }
      .padding({ left: 18, right: 18, top: 20, bottom: 40 })
      .width('100%')
    }
    .scrollable(ScrollDirection.Vertical)
    .width('100%')
    .height('100%')
    .backgroundColor('#000000')
  }
}
