// 对话页：发送管线（搜索分派→LLM→兜底→READ_HTML协议）+ WebView 渲染 + 历史抽屉 + 可取消加载
import { router, promptAction } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { common } from '@kit.AbilityKit';
import { buffer } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { AppConfig, ChatMsg, Conv, Msg, NetMode, SearchHit } from '../model/Types';
import { ConfigStore } from '../store/ConfigStore';
import { ConversationRepo } from '../store/ConversationRepo';
import { MessageRepo } from '../store/MessageRepo';
import { SearchRouter } from '../service/SearchRouter';
import { SearchClient } from '../service/SearchClient';
import { LlmClient } from '../service/LlmClient';
import { HtmlGuard } from '../service/HtmlGuard';
import { HtmlExtractor } from '../service/HtmlExtractor';
import { PromptBuilder, HistoryTurn } from '../service/PromptBuilder';

// 引导页快捷问题（点击直接开讲）
class Chip {
  static readonly QUESTIONS: string[] = ['为什么天空是蓝色的？', '黑洞里面是什么？', '这周有什么科技大事？'];
}

@Entry
@Component
struct Chat {
  @State convId: number = 0;
  @State title: string = '';
  @State html: string = '';
  @State turns: Msg[] = [];
  @State input: string = '';
  @State loading: boolean = false;
  @State loadingText: string = '正在写讲解…';
  @State netOn: boolean = false;
  @State showHistory: boolean = false;
  @State genId: number = 0;

  private webCtrl: webview.WebviewController = new webview.WebviewController();  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  aboutToAppear(): void {
    const p = router.getParams() as Record<string, number>;
    this.convId = p['convId'];
    this.init();
  }

  private async init(): Promise<void> {
    if (this.convId <= 0) {
      // 草稿会话：尚未落库，无历史可查
      this.title = '新对话';
      return;
    }
    const convs: Conv[] = await ConversationRepo.list(this.ctx());
    for (let i = 0; i < convs.length; i++) {
      if (convs[i].id === this.convId) {
        this.title = convs[i].title;
        break;
      }
    }
    this.turns = await MessageRepo.listByConv(this.ctx(), this.convId);
    // 最近一轮有内容就直接渲染（从列表重进不丢讲解）
    for (let i = this.turns.length - 1; i >= 0; i--) {
      if (this.turns[i].role === 'assistant') {
        this.setHtml(this.turns[i].content);
        break;
      }
    }
  }

  // walks 消息列表：user→当前问题；assistant→推入 {turnNo, question, plain}
  private buildHistory(turns: Msg[]): HistoryTurn[] {
    const out: HistoryTurn[] = [];
    let curQ = '';
    for (let i = 0; i < turns.length; i++) {
      const m: Msg = turns[i];
      if (m.role === 'user') {
        curQ = m.content;
      } else {
        const h = new HistoryTurn();
        h.turnNo = m.turnNo;
        h.question = curQ;
        h.plain = m.plain;
        out.push(h);
      }
    }
    return out;
  }

  private async refreshTurns(): Promise<void> {
    this.turns = await MessageRepo.listByConv(this.ctx(), this.convId);
  }

  private toast(msg: string): void {
    promptAction.showToast({ message: msg, duration: 2500 });
  }

  private cancelLoading(): void {
    this.genId += 1;
    this.loading = false;
  }

  private openTurn(turnNo: number): void {
    this.loadTurn(turnNo);
    this.showHistory = false;
  }

  private async loadTurn(turnNo: number): Promise<void> {
    const html: string = await MessageRepo.getHtmlByTurn(this.ctx(), this.convId, turnNo);
    if (html !== '') {
      this.setHtml(html);
    }
  }

  // 统一渲染入口：loadData 明文会把 CSS 的 #/% 当 URI 语法截断，必须 base64
  // webReady/pendingHtml 双标志覆盖 attach 与 init 的两种到达顺序
  private webReady: boolean = false;
  private pendingHtml: string = '';

  private setHtml(html: string): void {
    this.html = html;
    this.pendingHtml = html;
    if (this.webReady) {
      this.doLoad(html);
    }
  }

  private doLoad(html: string): void {
    const b64: string = buffer.from(html, 'utf-8').toString('base64');
    this.webCtrl.loadData(b64, 'text/html', 'base64');
  }

  private async send(): Promise<void> {
    const q: string = this.input.trim();
    if (q === '' || this.loading) { return; }
    this.input = '';
    const gen: number = this.genId + 1;
    this.genId = gen;
    this.loading = true;
    this.loadingText = '准备中…';
    try {
      const cfg: AppConfig = await ConfigStore.load(this.ctx());
      if (cfg.baseUrl === '' || cfg.apiKey === '' || cfg.model === '') {
        this.toast('请先在设置页配置 API 地址/密钥/模型');
        return;
      }
      // 草稿会话：首次发问才落库（点新对话即退不会留记录）
      if (this.convId <= 0) {
        this.convId = await ConversationRepo.create(this.ctx(), '新对话');
      }
      await MessageRepo.addUser(this.ctx(), this.convId, q);
      if (gen !== this.genId) { return; }

      // 1. 搜索路由分派
      const mode: NetMode = SearchRouter.resolve(cfg);
      const useTool: boolean = this.netOn && mode === NetMode.BUILTIN;
      const hits: SearchHit[] = [];
      if (this.netOn && mode === NetMode.EXTERNAL) {
        this.loadingText = '联网搜索中…';
        const sc = new SearchClient(cfg);
        const got: SearchHit[] = await sc.search(q);
        for (let i = 0; i < got.length; i++) { hits.push(got[i]); }
        if (hits.length === 0) {
          this.toast('没搜到结果，用模型已有知识回答');
        }
      } else if (this.netOn && mode === NetMode.NONE) {
        this.toast('当前模型端点不支持内置联网，且未配置独立搜索 key');
      }
      if (gen !== this.genId) { return; }

      // 2. 组 prompt（历史=问题+纯文本要点；搜索结果注入）
      this.loadingText = '正在写讲解…';
      const turns: Msg[] = await MessageRepo.listByConv(this.ctx(), this.convId);
      const history: HistoryTurn[] = this.buildHistory(turns);
      const messages: ChatMsg[] = [
        { role: 'system', content: PromptBuilder.SYSTEM } as ChatMsg,
        { role: 'user', content: PromptBuilder.buildUserPrompt(history, q, hits) } as ChatMsg
      ];

      // 3. 调 LLM（非流式，180s 超时）
      const llm = new LlmClient(cfg);
      let raw: string = await llm.chat(messages, useTool);
      if (gen !== this.genId) { return; }

      // 4. READ_HTML 协议：模型要某轮全文→注入后重发，最多一次
      const marker: number | null = HtmlGuard.detectReadMarker(raw);
      if (marker !== null && marker >= 1) {
        const prev: string = await MessageRepo.getHtmlByTurn(this.ctx(), this.convId, marker);
        if (prev !== '') {
          messages.push({ role: 'assistant', content: '[[READ_HTML:' + marker + ']]' } as ChatMsg);
          messages.push({ role: 'user', content: PromptBuilder.buildReadRetryPrompt(marker, prev, q) } as ChatMsg);
          raw = await llm.chat(messages, useTool);
          if (gen !== this.genId) { return; }
        }
      }

      // 5. 兜底校验：非合法 HTML 自动重试一次
      let html: string | null = HtmlGuard.sanitize(raw);
      if (html === null) {
        raw = await llm.chat(messages, useTool);
        if (gen !== this.genId) { return; }
        html = HtmlGuard.sanitize(raw);
        if (html === null) { throw new Error('回答不是合法 HTML，请重试'); }
      }

      // 6. 入库（plain 由 app 侧剥标签）+ 渲染
      await MessageRepo.addAssistant(this.ctx(), this.convId, html, HtmlExtractor.stripTags(html));
      await ConversationRepo.setTitleIfPlaceholder(this.ctx(), this.convId, q);
      await ConversationRepo.touch(this.ctx(), this.convId);
      const convs: Conv[] = await ConversationRepo.list(this.ctx());
      for (let i = 0; i < convs.length; i++) {
        if (convs[i].id === this.convId) { this.title = convs[i].title; break; }
      }
      this.html = html;
      this.setHtml(html);
      await this.refreshTurns();
    } catch (e) {
      hilog.error(0x0000, 'eli5', 'send failed: %{public}s', (e as Error).message);
      this.toast('出错了：' + (e as Error).message);
    } finally {
      if (gen === this.genId) { this.loading = false; }
    }
  }

  private webSrc(): string {
    return 'about:blank';
  }

  build() {
    Stack() {
      Column() {
        // ===== 顶栏 =====
        Row({ space: 10 }) {
          Text('‹ 返回')
            .fontSize(16)
            .fontColor('#B25E00')
            .onClick(() => {
              router.back();
            })
          Text(this.title)
            .fontSize(19)
            .fontWeight(FontWeight.Medium)
            .fontColor('#3D3325')
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
          Button('历史')
            .fontSize(14)
            .height(34)
            .fontColor('#B25E00')
            .backgroundColor('#FFFFFFA6')
            .border({ width: 1, color: '#FFFFFFCC' })
            .onClick(() => {
              this.showHistory = true;
            })
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 12, bottom: 10 })
        .backgroundColor('#F2EADCB0')
        .backgroundBlurStyle(BlurStyle.Thin)
        .border({ width: { bottom: 1 }, color: '#FFFFFFCC' })

        // ===== 讲解渲染区 =====
        Stack() {
          Web({ src: this.webSrc(), controller: this.webCtrl })
            .darkMode(WebDarkMode.Auto)
            .onControllerAttached(() => {
              this.webReady = true;
              if (this.pendingHtml !== '') {
                this.doLoad(this.pendingHtml);
              }
            })
            .width('100%')
          if (this.html === '') {
            Stack() {
              // 引导态光斑：与列表/设置页同族的柔光玻璃底
              Column()
                .width(340)
                .height(340)
                .position({ x: -90, y: 180 })
                .borderRadius(240)
                .backgroundColor('#4DFFD75E')
                .blur(90)
              Column()
                .width(360)
                .height(360)
                .position({ x: 220, y: 620 })
                .borderRadius(260)
                .backgroundColor('#407CC4FF')
                .blur(95)

              Column({ space: 12 }) {
                Text('🌤️').fontSize(60)
                Text('问我任何问题')
                  .fontSize(20)
                  .fontWeight(FontWeight.Medium)
                  .fontColor('#3D3325')
                Text('我会用大白话和图讲给你听')
                  .fontSize(14)
                  .fontColor('#8A7A5A')
                // 快捷问题：点击直接开讲，降低冷启动门槛
                Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Center }) {
                  ForEach(Chip.QUESTIONS, (q: string) => {
                    Text(q)
                      .fontSize(14)
                      .fontColor('#7A5B1E')
                      .backgroundColor('#FFFFFFA6')
                      .border({ width: 1, color: '#FFFFFFCC' })
                      .borderRadius(18)
                      .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                      .margin(6)
                      .onClick(() => {
                        this.input = q;
                        this.send();
                      })
                  }, (q: string) => q)
                }
                .width('86%')
                .margin({ top: 10 })
              }
              .width('100%')
              .height('100%')
              .justifyContent(FlexAlign.Center)
              .backgroundColor('#FAF6EF')
            }
            .width('100%')
            .height('100%')
          }
        }
        .layoutWeight(1)
        .width('100%')

        // ===== 底部输入区 =====
        Row({ space: 10 }) {
          // 联网开关胶囊：开=暖橙底白字，关=白玻璃暖灰字
          Row({ space: 5 }) {
            Text('🌐').fontSize(14)
            Text('联网').fontSize(14)
          }
          .padding({ left: 12, right: 12, top: 9, bottom: 9 })
          .borderRadius(20)
          .backgroundColor(this.netOn ? '#E8930C' : '#FFFFFFA6')
          .border({ width: 1, color: '#FFFFFFCC' })
          .onClick(() => { this.netOn = !this.netOn; })

          TextInput({ text: this.input, placeholder: '想弄懂什么？' })
            .type(InputType.Normal)
            .layoutWeight(1)
            .height(42)
            .backgroundColor('#FFFFFFA6')
            .borderRadius(21)
            .border({ width: 1, color: '#FFFFFFCC' })
            .enabled(!this.loading)
            .onChange((v: string) => { this.input = v; })
            .onSubmit(() => { this.send(); })
          Button(this.loading ? '…' : '发送')
            .fontSize(15)
            .height(40)
            .fontColor('#FFFFFF')
            .backgroundColor(this.loading ? '#E8DCC4' : '#E8930C')
            .enabled(!this.loading)
            .onClick(() => { this.send(); })
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 8, bottom: 14 })
        .alignItems(VerticalAlign.Center)
        .backgroundColor('#F2EADCB0')
        .backgroundBlurStyle(BlurStyle.Thin)
        .border({ width: { top: 1 }, color: '#FFFFFFCC' })
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#FAF6EF')

      // ===== 加载遮罩（白玻璃磨砂，与全局玻璃风一致） =====
      if (this.loading) {
        Column({ space: 14 }) {
          LoadingProgress().width(56).height(56).color('#E8930C')
          Text(this.loadingText).fontSize(16).fontColor('#3D3325')
          Text('讲解需要一点时间，通常 30-90 秒')
            .fontSize(12).fontColor('#8A7A5A')
          Button('取消')
            .fontSize(14)
            .fontColor('#B25E00')
            .backgroundColor('#FFFFFFCC')
            .border({ width: 1, color: '#FFFFFF' })
            .onClick(() => { this.cancelLoading(); })
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#BFFAF6EF')
        .backgroundBlurStyle(BlurStyle.Regular)
      }

      // ===== 历史抽屉 =====
      if (this.showHistory) {
        Column() {
          Row() {
            Text('历史讲解').fontSize(18).fontWeight(FontWeight.Bold).fontColor('#3D3325').layoutWeight(1)
            Text('收起 ✕').fontSize(15).fontColor('#B25E00')
              .onClick(() => { this.showHistory = false; })
          }
          .width('100%')
          .padding(16)

          if (this.turns.length === 0) {
            Text('还没有讲解记录').fontSize(14).fontColor('#A08C66').padding(16)
          } else {
            List({ space: 8 }) {
              ForEach(this.turns, (m: Msg) => {
                if (m.role === 'assistant') {
                  ListItem() {
                    Column({ space: 4 }) {
                      Text(`第${m.turnNo}轮`)
                        .fontSize(12).fontColor('#B25E00')
                      Text(this.questionOf(m.turnNo))
                        .fontSize(15)
                        .maxLines(2)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                        .width('100%')
                    }
                    .alignItems(HorizontalAlign.Start)
                    .padding(12)
                    .borderRadius(12)
                    .backgroundColor('#FFFFFF8C')
                    .width('100%')
                    .onClick(() => { this.openTurn(m.turnNo); })
                  }
                }
              }, (m: Msg) => `${m.id}`)
            }
            .layoutWeight(1)
            .width('100%')
            .padding({ left: 12, right: 12 })
          }
        }
        .width('86%')
        .height('100%')
        .backgroundColor('#F7F1E6F5')
        .backgroundBlurStyle(BlurStyle.Regular)
        .alignItems(HorizontalAlign.Start)
      }
    }
    .width('100%')
    .height('100%')
  }

  // 从 turns 里找该轮对应的问题
  private questionOf(turnNo: number): string {
    let q = '';
    for (let i = 0; i < this.turns.length; i++) {
      const m: Msg = this.turns[i];
      if (m.role === 'user') { q = m.content; }
      if (m.role === 'assistant' && m.turnNo === turnNo) { break; }
    }
    return q === '' ? '（无问题记录）' : q;
  }
}
