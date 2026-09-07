// 对话页（打开即对话的家）：消息流 List（用户气泡/回答气泡/画布卡）+ AgentLoop 发送管线
// + 内联过程时间线卡 + 侧抽屉（会话列表 + 历史轮次双段）+ @提及解析
import { router, promptAction } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { common } from '@kit.AbilityKit';
import { buffer } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { AppConfig, AgentSkill, ChatMsg, Conv, Msg } from '../model/Types';
import { ConfigStore } from '../store/ConfigStore';
import { ConversationRepo } from '../store/ConversationRepo';
import { MessageRepo } from '../store/MessageRepo';
import { SkillRepo } from '../store/SkillRepo';
import { AgentLoop, LoopEvent, LoopResult } from '../service/AgentLoop';
import { HtmlGuard, ReplyKind } from '../service/HtmlGuard';
import { CanvasCard } from './CanvasCard';
import { HistoryDrawer } from './HistoryDrawer';
import { GuidePage } from './GuidePage';

// 消息流渲染单元：role='user'|'assistant'；kind='html'|'text'（画布卡/文本气泡）
class RenderMsg {
  id: number = 0;
  role: string = '';
  content: string = '';
  kind: string = ReplyKind.TEXT;
}

// 时间线事件 → 展示文案（tool_done 的 detail 由引擎截到 80 字，这里再截 40 字防换行刷屏）
function timelineLabel(e: LoopEvent): string {
  if (e.kind === 'tool_start') { return `🔍 调用 ${e.toolName}…`; }
  if (e.kind === 'tool_done') {
    const d: string = e.detail !== '' ? ' ' + e.detail.substring(0, 40) : '';
    return `✓ 完成${d}`;
  }
  if (e.kind === 'answer') { return '✍️ 整理回答…'; }
  if (e.kind === 'limit') { return '已达步数上限'; }
  return e.kind;
}

@Entry
@Component
struct Chat {
  @State convId: number = 0;
  @State title: string = '';
  @State msgs: RenderMsg[] = [];
  @State timeline: LoopEvent[] = [];
  @State turns: Msg[] = [];
  @State convs: Conv[] = [];
  @State input: string = '';
  @State loading: boolean = false;
  @State showHistory: boolean = false;
  @State genId: number = 0;
  private nextId: number = 1;
  private listScroller: Scroller = new Scroller();

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  aboutToAppear(): void {
    const p = router.getParams() as Record<string, number>;
    if (p !== undefined && p['convId'] !== undefined) {
      this.convId = p['convId'];
    } else {
      this.convId = -1;
    }
    this.init();
  }

  // 加载会话：turns 逐条转 RenderMsg，assistant 按 ReplyKind.detect 判画布卡/气泡
  // （存量旧 eli5 HTML 轮自动变画布卡，无需人工迁移）
  private async init(): Promise<void> {
    this.msgs = [];
    this.nextId = 1;
    await this.refreshConvs();
    if (this.convId <= 0) {
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
    for (let i = 0; i < this.turns.length; i++) {
      const m: Msg = this.turns[i];
      const rm = new RenderMsg();
      rm.id = this.allocId();
      rm.role = m.role;
      rm.content = m.content;
      rm.kind = m.role === 'assistant' ? ReplyKind.detect(m.content) : ReplyKind.TEXT;
      this.msgs.push(rm);
    }
    this.scrollToBottom();
  }

  private allocId(): number {
    const id: number = this.nextId;
    this.nextId += 1;
    return id;
  }

  private async refreshTurns(): Promise<void> {
    this.turns = await MessageRepo.listByConv(this.ctx(), this.convId);
  }

  private async refreshConvs(): Promise<void> {
    this.convs = await ConversationRepo.list(this.ctx());
  }

  private toast(msg: string): void {
    promptAction.showToast({ message: msg, duration: 2500 });
  }

  private scrollToBottom(): void {
    this.listScroller.scrollEdge(Edge.Bottom);
  }

  private cancelLoading(): void {
    this.genId += 1;
    this.loading = false;
  }

  // 发送管线：@提及解析 → 构建历史 → AgentLoop.run（onEvent 实时推时间线）→ 画布卡/气泡入库渲染
  private async send(): Promise<void> {
    const q0: string = this.input.trim();
    if (q0 === '' || this.loading) { return; }
    this.input = '';
    const gen: number = this.genId + 1;
    this.genId = gen;
    this.loading = true;
    this.timeline = [];

    // 用户气泡先上屏（草稿会话此时尚未落库）
    const um = new RenderMsg();
    um.id = this.allocId();
    um.role = 'user';
    um.content = q0;
    this.msgs.push(um);
    this.scrollToBottom();

    // 净空语义：草稿会话 convId<=0 时先不落库，首个 loop 事件到来再 create+写 user 行；
    // 无任何事件前取消 → 不留任何记录。persistPromise 保证 user 行先于 assistant 行入库。
    let persisted: boolean = false;
    let persistPromise: Promise<void> = Promise.resolve();

    try {
      const cfg: AppConfig = await ConfigStore.load(this.ctx());
      if (cfg.baseUrl === '' || cfg.apiKey === '' || cfg.model === '') {
        this.toast('请先在设置页配置 API 地址/密钥/模型');
        this.msgs.pop();
        return;
      }

      // @提及解析：/@([\w\u4e00-\u9fa5\-]+)/ 命中技能库 → forcedSkill，并从 question 剔除该段
      let q: string = q0;
      let forcedSkill: AgentSkill | null = null;
      const m: RegExpMatchArray | null = q0.match(/@([\w\u4e00-\u9fa5\-]+)/);
      if (m !== null && m.length > 1) {
        const skill: AgentSkill | null = await SkillRepo.getByName(this.ctx(), m[1]);
        if (skill !== null) {
          forcedSkill = skill;
          q = q0.replace(m[0], '').trim();
          if (q === '') { q = q0; }
        }
      }

      // 历史 turns → ChatMsg[]（只含 user/assistant 文本内容；assistant 用 plain 防大 HTML 撑上下文）
      const history: ChatMsg[] = [];
      if (this.convId > 0) {
        const past: Msg[] = await MessageRepo.listByConv(this.ctx(), this.convId);
        for (let i = 0; i < past.length; i++) {
          const t: Msg = past[i];
          if (t.role === 'user') {
            const h: ChatMsg = { role: 'user', content: t.content };
            history.push(h);
          } else if (t.role === 'assistant') {
            const h: ChatMsg = { role: 'assistant', content: t.plain !== '' ? t.plain : t.content };
            history.push(h);
          }
        }
      }

      const onEvent = (e: LoopEvent): void => {
        if (gen !== this.genId) { return; }
        this.timeline.push(e);
        this.scrollToBottom();
        if (!persisted) {
          persisted = true;
          persistPromise = (async (): Promise<void> => {
            if (this.convId <= 0) {
              this.convId = await ConversationRepo.create(this.ctx(), '新对话');
            }
            await MessageRepo.add(this.ctx(), this.convId, 'user', q0, ReplyKind.TEXT);
          })();
        }
      };

      const r: LoopResult = await AgentLoop.run(cfg, this.ctx(), history, q, forcedSkill, onEvent,
        undefined, (): boolean => { return gen !== this.genId; });
      await persistPromise;
      if (gen !== this.genId) { return; }

      // 输出形态：isHtml → sanitize 后入库渲染画布卡（同时保证 reload 时 detect 判 html 成立）；
      // 否则纯文本气泡
      let content: string = r.answer;
      let kind: string = ReplyKind.TEXT;
      if (r.isHtml) {
        const safe: string | null = HtmlGuard.sanitize(content);
        if (safe !== null) {
          content = safe;
          kind = ReplyKind.HTML;
        }
      }
      await MessageRepo.add(this.ctx(), this.convId, 'assistant', content, kind);
      await ConversationRepo.setTitleIfPlaceholder(this.ctx(), this.convId, q0);
      await ConversationRepo.touch(this.ctx(), this.convId);
      const convs: Conv[] = await ConversationRepo.list(this.ctx());
      for (let i = 0; i < convs.length; i++) {
        if (convs[i].id === this.convId) { this.title = convs[i].title; break; }
      }
      const am = new RenderMsg();
      am.id = this.allocId();
      am.role = 'assistant';
      am.content = content;
      am.kind = kind;
      this.msgs.push(am);
      this.scrollToBottom();
      await this.refreshTurns();
      await this.refreshConvs();
    } catch (e) {
      const em: string = (e as Error).message;
      try { await persistPromise; } catch (pe) {
        hilog.error(0x0000, 'eli5', 'persist failed: %{public}s', (pe as Error).message);
      }
      if (em !== 'AGENT_LOOP_CANCELLED') {
        hilog.error(0x0000, 'eli5', 'send failed: %{public}s', em);
        this.toast('出错了：' + em);
      }
    } finally {
      if (gen === this.genId) { this.loading = false; }
    }
  }

  // ===== 抽屉动作 =====

  private openHistory(): void {
    this.refreshConvs();
    this.showHistory = true;
  }

  private startNewConversation(): void {
    this.showHistory = false;
    if (this.convId <= 0) { return; }
    const p: Record<string, number> = { 'convId': -1 };
    router.replaceUrl({ url: 'pages/Chat', params: p });
  }

  // 点会话 = 重新 init 该会话（简化实现：replaceUrl Chat 带 convId）
  private openConversation(id: number): void {
    this.showHistory = false;
    if (id === this.convId) { return; }
    const p: Record<string, number> = { 'convId': id };
    router.replaceUrl({ url: 'pages/Chat', params: p });
  }

  private confirmDelete(conv: Conv): void {
    AlertDialog.show({
      title: '删除对话',
      message: `确定删除「${conv.title}」吗？`,
      primaryButton: { value: '取消', action: () => { } },
      secondaryButton: {
        value: '删除',
        fontColor: '#FF453A',
        action: () => { this.doDelete(conv.id); }
      }
    });
  }

  private async doDelete(id: number): Promise<void> {
    await ConversationRepo.remove(this.ctx(), id);
    promptAction.showToast({ message: '已删除', duration: 1500 });
    await this.refreshConvs();
    if (id === this.convId) {
      const p: Record<string, number> = { 'convId': -1 };
      router.replaceUrl({ url: 'pages/Chat', params: p });
    }
  }

  // 点历史轮次 → 滚动消息流到对应 assistant 消息（轮次号 = 第 n 条 assistant 消息）
  private openTurn(turnNo: number): void {
    this.showHistory = false;
    const idx: number = this.msgIndexOfTurn(turnNo);
    if (idx >= 0) {
      this.listScroller.scrollToIndex(idx, true);
    }
  }

  private msgIndexOfTurn(turnNo: number): number {
    let seen: number = 0;
    for (let i = 0; i < this.msgs.length; i++) {
      if (this.msgs[i].role === 'assistant') {
        seen += 1;
        if (seen === turnNo) { return i; }
      }
    }
    return -1;
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
              this.openHistory();
            })
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 12, bottom: 10 })
        .backgroundColor('#F2EADCB0')
        .backgroundBlurStyle(BlurStyle.Thin)
        .border({ width: { bottom: 1 }, color: '#FFFFFFCC' })

        // ===== 消息流 =====
        Stack() {
          List({ space: 12, scroller: this.listScroller }) {
            ForEach(this.msgs, (m: RenderMsg) => {
              ListItem() {
                Row() {
                  if (m.role === 'user') {
                    Blank()
                    Text(m.content)
                      .fontSize(15)
                      .fontColor('#FFFFFF')
                      .backgroundColor('#E8930C')
                      .borderRadius(16)
                      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                      .constraintSize({ maxWidth: '78%' })
                  } else if (m.kind === ReplyKind.HTML) {
                    Column() {
                      CanvasCard({ html: m.content })
                    }
                    .width('100%')
                  } else {
                    Text(m.content)
                      .fontSize(15)
                      .fontColor('#3D3325')
                      .backgroundColor('#FFFFFFA6')
                      .borderRadius(16)
                      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                      .constraintSize({ maxWidth: '86%' })
                    Blank()
                  }
                }
                .width('100%')
                .alignItems(VerticalAlign.Top)
              }
            }, (m: RenderMsg) => `msg_${m.id}`)

            // 时间线卡：只在 loading 时渲染于流末尾（旧全屏遮罩已删）
            if (this.loading) {
              ListItem() {
                Column({ space: 8 }) {
                  Row({ space: 8 }) {
                    LoadingProgress().width(18).height(18).color('#E8930C')
                    Text('正在处理…')
                      .fontSize(13)
                      .fontColor('#8A7A5A')
                      .layoutWeight(1)
                    Button('取消')
                      .fontSize(13)
                      .height(30)
                      .fontColor('#B25E00')
                      .backgroundColor('#FFFFFFCC')
                      .border({ width: 1, color: '#FFFFFF' })
                      .onClick(() => { this.cancelLoading(); })
                  }
                  .width('100%')
                  .alignItems(VerticalAlign.Center)

                  ForEach(this.timeline, (e: LoopEvent, idx: number) => {
                    Text(timelineLabel(e))
                      .fontSize(13)
                      .fontColor(e.kind === 'tool_done' ? '#4C7A28' : '#6B5B3E')
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .width('100%')
                  }, (e: LoopEvent, idx: number) => `tl_${idx}`)
                }
                .alignItems(HorizontalAlign.Start)
                .padding(12)
                .borderRadius(14)
                .backgroundColor('#FFFFFFA6')
                .border({ width: 1, color: '#FFFFFFCC' })
                .width('100%')
              }
            }
          }
          .layoutWeight(1)
          .width('100%')
          .padding({ left: 14, right: 14, top: 12, bottom: 12 })
          .cachedCount(4)

          // ===== 引导态：无任何消息时 =====
          if (this.msgs.length === 0) {
            GuidePage({
              onAsk: (q: string): void => {
                this.input = q;
                this.send();
              }
            })
          }
        }
        .layoutWeight(1)
        .width('100%')

        // ===== 底部输入区 =====
        Row({ space: 10 }) {
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
          Button(this.loading ? '停止' : '发送')
            .fontSize(15)
            .height(40)
            .fontColor(this.loading ? '#B25E00' : '#FFFFFF')
            .backgroundColor(this.loading ? '#FFFFFFCC' : '#E8930C')
            .onClick(() => {
              if (this.loading) { this.cancelLoading(); } else { this.send(); }
            })
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

      // ===== 侧抽屉：上半会话列表 + 下半历史轮次 =====
      if (this.showHistory) {
        HistoryDrawer({
          convs: this.convs,
          turns: this.turns,
          onClose: (): void => { this.showHistory = false; },
          onNewConversation: (): void => { this.startNewConversation(); },
          onOpenSettings: (): void => {
            this.showHistory = false;
            router.pushUrl({ url: 'pages/Settings' });
          },
          onOpenConversation: (id: number): void => { this.openConversation(id); },
          onDeleteConversation: (c: Conv): void => { this.confirmDelete(c); },
          onOpenTurn: (turnNo: number): void => { this.openTurn(turnNo); }
        })
      }
    }
    .width('100%')
    .height('100%')
  }

}
