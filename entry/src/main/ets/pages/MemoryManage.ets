// 忆枢记忆管理页（协议 §4，M1c 对应物）：核心块查看/编辑/重置种子 + 归档列表/搜索/单删/清空。
// 模型自主管理的风险对价：用户对记忆永远有「看、改、删」的闸门。
import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { ThemeTokens } from '../theme/ThemeTokens';
import { SkinScheme, CLEAR_LIGHT } from '../theme/Schemes';
import { MemoryStore, CoreBlock, ArchivalRow } from '../memory/MemoryStore';
import { MemoryTools } from '../memory/MemoryTools';
import { AGENT_SYSTEM } from '../service/AgentLoop';

const DOMAIN = 0x0000;

@Entry
@Component
struct MemoryManage {
  @StorageProp('statusBarH') statusBarH: number = 0;
  @StorageProp('scheme') scheme: SkinScheme = CLEAR_LIGHT;
  @State persona: string = '';
  @State human: string = '';
  @State personaEditing: boolean = false;
  @State humanEditing: boolean = false;
  @State editBuf: string = '';
  @State editBlock: string = '';
  @State archival: ArchivalRow[] = [];
  @State query: string = '';
  @State archivalCount: number = 0;
  private store: MemoryStore | null = null;

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  aboutToAppear(): void {
    this.reload();
  }

  private async store_(): Promise<MemoryStore> {
    if (this.store === null) {
      this.store = await MemoryStore.create(this.ctx());
    }
    return this.store;
  }

  private async reload(): Promise<void> {
    const s = await this.store_();
    const personaBlk: CoreBlock | null = await s.getCoreBlock(MemoryTools.BLOCK_PERSONA);
    const humanBlk: CoreBlock | null = await s.getCoreBlock(MemoryTools.BLOCK_HUMAN);
    this.persona = personaBlk !== null ? personaBlk.content : '';
    this.human = humanBlk !== null ? humanBlk.content : '';
    hilog.info(DOMAIN, 'yunkai', 'mem reload: persona=%{public}d human=%{public}d', this.persona.length, this.human.length);
    this.archivalCount = await s.archivalCount();
    if (this.query.trim() === '') {
      // 全量列表（管理页要可预测的完整视图；万条软上限协议 §2，超限只提示不拦截）
      this.archival = await s.allArchival();
    } else {
      // 管理页搜索用 contains 过滤（与工具链路 BM25 检索语义不同：管理视图要全部命中行）
      const all = await s.allArchival();
      const q = this.query.toLowerCase();
      this.archival = all.filter((r: ArchivalRow): boolean =>
        r.content.toLowerCase().includes(q) || r.type.toLowerCase().includes(q));
    }
  }

  private async saveBlock(block: string): Promise<void> {
    const limit = block === MemoryTools.BLOCK_PERSONA ? MemoryTools.CORE_PERSONA_LIMIT : MemoryTools.CORE_HUMAN_LIMIT;
    if (this.editBuf.length > limit) {
      promptAction.showToast({ message: `超出预算（${this.editBuf.length}/${limit}）` });
      return;
    }
    (await this.store_()).putCoreBlock(block, this.editBuf);
    if (block === MemoryTools.BLOCK_PERSONA) {
      this.persona = this.editBuf;
      this.personaEditing = false;
    } else {
      this.human = this.editBuf;
      this.humanEditing = false;
    }
    promptAction.showToast({ message: '已保存' });
  }

  private async resetPersona(): Promise<void> {
    (await this.store_()).putCoreBlock(MemoryTools.BLOCK_PERSONA, AGENT_SYSTEM);
    this.persona = AGENT_SYSTEM;
    this.personaEditing = false;
    promptAction.showToast({ message: 'persona 已重置为初始种子' });
  }

  private typeLabel(t: string): string {
    if (t === 'episode') { return '情节'; }
    if (t === 'preference') { return '偏好'; }
    if (t === 'identity') { return '身份'; }
    return '事实';
  }

  @Builder
  blockCard(name: string) {
    // 注意：@Builder 按值传参不响应状态更新，块内容/编辑态必须在本体内直读 this.*（ArkUI 响应式纪律）
    Column({ space: 6 }) {
      if (name === 'human') {
        Row({ space: 8 }) {
          Text('human · 用户档案')
            .fontSize(14).fontColor(this.scheme.textHi).layoutWeight(1)
          Text(`${this.human.length}/${MemoryTools.CORE_HUMAN_LIMIT}`)
            .fontSize(11).fontColor(this.scheme.textLow)
          Text(this.humanEditing ? '取消' : '编辑')
            .fontSize(12).fontColor(this.scheme.accent)
            .onClick((): void => {
              this.editBlock = name;
              this.editBuf = this.human;
              this.humanEditing = !this.humanEditing;
              this.personaEditing = false;
            })
        }.width('100%')
        if (this.humanEditing) {
          TextArea({ text: this.editBuf })
            .onChange((v: string) => { this.editBuf = v; })
            .fontSize(13)
            .height(120)
            .width('100%')
          Button('保存')
            .fontSize(13).height(32)
            .onClick((): void => { this.saveBlock(name); })
        } else {
          Text(this.human === '' ? '（空）' : this.human)
            .fontSize(12).fontColor(this.scheme.textMid)
            .width('100%').maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
      } else {
        Row({ space: 8 }) {
          Text('persona · 自我定义')
            .fontSize(14).fontColor(this.scheme.textHi).layoutWeight(1)
          Text(`${this.persona.length}/${MemoryTools.CORE_PERSONA_LIMIT}`)
            .fontSize(11).fontColor(this.scheme.textLow)
          Text('重置种子')
            .fontSize(12).fontColor(this.scheme.accent)
            .onClick((): void => { this.resetPersona(); })
          Text(this.personaEditing ? '取消' : '编辑')
            .fontSize(12).fontColor(this.scheme.accent)
            .onClick((): void => {
              this.editBlock = name;
              this.editBuf = this.persona;
              this.personaEditing = !this.personaEditing;
              this.humanEditing = false;
            })
        }.width('100%')
        if (this.personaEditing) {
          TextArea({ text: this.editBuf })
            .onChange((v: string) => { this.editBuf = v; })
            .fontSize(13)
            .height(120)
            .width('100%')
          Button('保存')
            .fontSize(13).height(32)
            .onClick((): void => { this.saveBlock(name); })
        } else {
          Text(this.persona === '' ? '（空）' : this.persona)
            .fontSize(12).fontColor(this.scheme.textMid)
            .width('100%').maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
      }
    }
    .alignItems(HorizontalAlign.Start)
    .padding(12)
    .borderRadius(ThemeTokens.R_CARD)
    .backgroundColor(this.scheme.glassBg)
    .border({ width: ThemeTokens.BORDER_W, color: this.scheme.glassBorder })
    .width('100%')
  }

  build() {
    Column() {
      // 顶栏：返回 + 标题
      Row({ space: 12 }) {
        Text('‹')
          .fontSize(18).fontColor(this.scheme.textHi)
          .padding({ left: 12, right: 12 })
          .onClick((): void => { router.back(); })
        Text('记忆').fontSize(22).fontColor(this.scheme.textHi)
        Blank()
      }
      .width('100%')
      .padding({ top: this.statusBarH + 8, bottom: 8, left: 8, right: 18 })

      Scroll() {
        Column({ space: 14 }) {
          if (this.archivalCount > 10000) {
            Text(`归档已达 ${this.archivalCount} 条（软上限 10000，建议清理）`)
              .fontSize(12).fontColor(this.scheme.accent).width('100%')
          }

          Text('核心记忆').fontSize(16).fontColor(this.scheme.textMid).width('100%')
          this.blockCard('human')
          this.blockCard('persona')

          Row({ space: 8 }) {
            Text('归档').fontSize(16).fontColor(this.scheme.textMid).layoutWeight(1)
            TextInput({ placeholder: '搜索归档', text: this.query })
              .onChange((v: string) => { this.query = v; this.reload(); })
              .fontSize(13).height(34).width(160)
            Text('清空')
              .fontSize(13).fontColor(this.scheme.accent)
              .onClick((): void => {
                promptAction.showDialog({
                  title: '清空归档',
                  message: '将删除全部归档记忆，不可恢复。核心记忆不受影响。',
                  buttons: [{ text: '取消', color: '#888888' }, { text: '清空', color: '#E64A45' }]
                }).then((res) => {
                  if (res.index === 1) {
                    this.store_().then((s) => s.clearArchival()).then(() => this.reload());
                  }
                });
              })
          }.width('100%')

          if (this.archival.length === 0) {
            Text(this.query === '' ? '暂无归档记忆' : `没有匹配「${this.query}」的归档`)
              .fontSize(13).fontColor(this.scheme.textLow).width('100%').margin({ top: 20 })
          }
          List({ space: 8 }) {
            ForEach(this.archival, (r: ArchivalRow) => {
              ListItem() {
                Column({ space: 4 }) {
                  Row({ space: 6 }) {
                    Text(this.typeLabel(r.type)).fontSize(10).fontColor(this.scheme.accent)
                    Text(r.hitCount > 0 ? `命中 ${r.hitCount}` : '').fontSize(10).fontColor(this.scheme.textLow)
                    Blank()
                    Text('删除').fontSize(11).fontColor(this.scheme.accent)
                      .onClick((): void => {
                        this.store_().then((s) => s.deleteArchival(r.id)).then(() => this.reload());
                      })
                  }.width('100%')
                  Text(r.content).fontSize(12).fontColor(this.scheme.textMid).width('100%')
                }
                .alignItems(HorizontalAlign.Start)
                .padding(10)
                .borderRadius(ThemeTokens.R_CARD)
                .backgroundColor(this.scheme.glassBg)
                .width('100%')
              }
            }, (r: ArchivalRow) => `arc_${r.id}`)
          }
          .width('100%')
          .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 18, right: 18, bottom: 20 })
      }
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .padding({ bottom: this.statusBarH > 0 ? 0 : 12 })
  }
}
