// 技能库页：技能列表（内置标记）+ 新建表单 + 粘贴导入 + 长按删除（非内置）。
// 玻璃化风格与 Settings 一致：壁纸底 + 白玻璃卡 + accent 主按钮（明暗跟随 token）。
import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { AgentSkill } from '../model/Types';
import { SkillRepo } from '../store/SkillRepo';
import { SkillImporter } from '../service/SkillImporter';
import { ThemeTokens } from '../theme/ThemeTokens';
import { WallpaperLayer } from '../common/WallpaperLayer';

@Entry
@Component
struct SkillManage {
  @StorageProp('statusBarH') statusBarH: number = 0;
  @State skills: AgentSkill[] = [];
  // 表单模式：none=纯列表；create=新建；import=粘贴导入（两种表单互斥）
  @State mode: string = 'none';
  @State formName: string = '';
  @State formDesc: string = '';
  @State formContent: string = '';
  @State importText: string = '';

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  aboutToAppear(): void {
    this.refresh();
  }

  private toast(msg: string): void {
    promptAction.showToast({ message: msg, duration: 2500 });
  }

  private async refresh(): Promise<void> {
    this.skills = await SkillRepo.list(this.ctx());
  }

  private closeForms(): void {
    this.mode = 'none';
    this.formName = '';
    this.formDesc = '';
    this.formContent = '';
    this.importText = '';
  }

  private openCreate(): void {
    this.formName = '';
    this.formDesc = '';
    this.formContent = '';
    this.mode = 'create';
  }

  private openImport(): void {
    this.importText = '';
    this.mode = 'import';
  }

  private async saveNew(): Promise<void> {
    const name: string = this.formName.trim();
    if (name === '') {
      this.toast('请填写技能名');
      return;
    }
    const content: string = this.formContent.trim();
    if (content === '') {
      this.toast('技能内容为空');
      return;
    }
    const id: number = await SkillRepo.insert(this.ctx(), name, this.formDesc.trim(), content, false);
    if (id < 0) {
      this.toast('技能名已存在');
      return;
    }
    this.closeForms();
    this.toast('已保存');
    await this.refresh();
  }

  private async doImport(): Promise<void> {
    const skill: AgentSkill | null = SkillImporter.parse(this.importText);
    if (skill === null) {
      this.toast('无法识别的格式');
      return;
    }
    if (skill.content.trim() === '') {
      this.toast('技能内容为空');
      return;
    }
    const id: number = await SkillRepo.insert(this.ctx(), skill.name, skill.description, skill.content, false);
    if (id < 0) {
      this.toast('技能名已存在');
      return;
    }
    this.closeForms();
    this.toast('已导入');
    await this.refresh();
  }

  // 长按卡片：内置技能不响应，仅自建技能弹删除确认
  private confirmDelete(skill: AgentSkill): void {
    if (skill.builtin) { return; }
    AlertDialog.show({
      title: '删除技能',
      message: `确定删除「${skill.name}」吗？`,
      primaryButton: { value: '取消', action: () => { } },
      secondaryButton: {
        value: '删除',
        fontColor: '#FF453A',
        action: () => { this.doDelete(skill.id); }
      }
    });
  }

  private async doDelete(id: number): Promise<void> {
    await SkillRepo.remove(this.ctx(), id);
    this.toast('已删除');
    await this.refresh();
  }

  build() {
    Stack() {
      WallpaperLayer()

      Column() {
        // ===== 顶部：返回玻璃圆钮 + 标题（避让状态栏） =====
        Row({ space: 12 }) {
          Button('‹')
            .width(34)
            .height(34)
            .fontSize(18)
            .fontColor($r('app.color.text_hi'))
            .backgroundColor($r('app.color.glass_bg'))
            .backdropBlur(20)
            .borderRadius(17)
            .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
            .padding(0)
            .onClick(() => { router.back(); })
          Text('技能库')
            .fontSize(24)
            .fontWeight(FontWeight.Medium)
            .fontColor($r('app.color.text_hi'))
            .fontFamily('Sora, HarmonyOS Sans')
          Blank()
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .margin({ top: this.statusBarH + 12, bottom: 6 })

        // ===== 列表区 =====
        Column() {
          if (this.skills.length === 0) {
            Column({ space: 10 }) {
              Text('还没有技能，点下方新建或粘贴导入')
                .fontSize(15)
                .fontColor($r('app.color.text_mid'))
                .textAlign(TextAlign.Center)
            }
            .width('100%')
            .height('100%')
            .justifyContent(FlexAlign.Center)
          } else {
            List({ space: 12 }) {
              ForEach(this.skills, (skill: AgentSkill) => {
                ListItem() {
                  Column({ space: 6 }) {
                    Row({ space: 8 }) {
                      Text(skill.name)
                        .fontSize(16)
                        .fontWeight(FontWeight.Bold)
                        .fontColor($r('app.color.text_hi'))
                        .maxLines(1)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                        .layoutWeight(1)
                      if (skill.builtin) {
                        Text('内置')
                          .fontSize(11)
                          .fontColor($r('app.color.accent'))
                          .padding({ left: 8, right: 8, top: 3, bottom: 3 })
                          .backgroundColor($r('app.color.glass_bg_strong'))
                          .borderRadius(9)
                      }
                    }
                    .width('100%')
                    .alignItems(VerticalAlign.Center)

                    Text(skill.description)
                      .fontSize(13)
                      .fontColor($r('app.color.text_mid'))
                      .maxLines(2)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .width('100%')
                  }
                  .alignItems(HorizontalAlign.Start)
                  .padding(14)
                  .borderRadius(16)
                  .backgroundColor($r('app.color.glass_bg'))
                  .backdropBlur(ThemeTokens.BLUR_CARD)
                  .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
                  .shadow({ radius: 16, color: '#33000000', offsetY: 6 })
                  .width('100%')
                  .gesture(LongPressGesture().onAction(() => { this.confirmDelete(skill); }))
                }
              }, (skill: AgentSkill) => skill.id.toString())
            }
            .width('100%')
            .height('100%')
            .scrollBar(BarState.Off)
          }
        }
        .layoutWeight(1)
        .width('100%')

        // ===== 新建表单（与导入互斥）=====
        if (this.mode === 'create') {
          Column({ space: 10 }) {
            Text('新建技能').fontSize(15).fontColor($r('app.color.text_mid')).width('100%')
            Text('技能名').fontSize(13).fontColor($r('app.color.text_hi')).width('100%')
            TextInput({ text: this.formName, placeholder: '英文或中文名，@名字 引用' })
              .type(InputType.Normal)
              .width('100%')
              .backgroundColor($r('app.color.glass_bg_strong'))
              .fontColor($r('app.color.text_hi'))
              .placeholderColor($r('app.color.text_low'))
              .borderRadius(12)
              .onChange((v: string) => { this.formName = v; })
            Text('简介').fontSize(13).fontColor($r('app.color.text_hi')).width('100%')
            TextInput({ text: this.formDesc, placeholder: '一句话说明这个技能做什么' })
              .type(InputType.Normal)
              .width('100%')
              .backgroundColor($r('app.color.glass_bg_strong'))
              .fontColor($r('app.color.text_hi'))
              .placeholderColor($r('app.color.text_low'))
              .borderRadius(12)
              .onChange((v: string) => { this.formDesc = v; })
            Text('内容').fontSize(13).fontColor($r('app.color.text_hi')).width('100%')
            TextArea({ text: this.formContent, placeholder: '技能正文（多行）' })
              .width('100%')
              .height(110)
              .backgroundColor($r('app.color.glass_bg_strong'))
              .fontColor($r('app.color.text_hi'))
              .placeholderColor($r('app.color.text_low'))
              .borderRadius(12)
              .onChange((v: string) => { this.formContent = v; })
            Row({ space: 10 }) {
              Button('取消', { type: ButtonType.Capsule })
                .layoutWeight(1)
                .height(40)
                .fontSize(15)
                .fontColor($r('app.color.accent'))
                .backgroundColor($r('app.color.glass_bg_strong'))
                .onClick(() => { this.closeForms(); })
              Button('保存', { type: ButtonType.Capsule })
                .layoutWeight(1)
                .height(40)
                .fontSize(15)
                .fontColor('#FFFFFF')
                .backgroundColor($r('app.color.accent'))
                .onClick(() => { this.saveNew(); })
            }
            .width('100%')
          }
          .alignItems(HorizontalAlign.Start)
          .padding(14)
          .borderRadius(18)
          .backgroundColor($r('app.color.glass_bg'))
          .backdropBlur(ThemeTokens.BLUR_CARD)
          .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
          .shadow({ radius: 20, color: '#40000000', offsetY: 8 })
          .width('100%')
          .margin({ top: 12 })
        }

        // ===== 粘贴导入表单（与新建互斥）=====
        if (this.mode === 'import') {
          Column({ space: 10 }) {
            Text('粘贴导入').fontSize(15).fontColor($r('app.color.text_mid')).width('100%')
            TextArea({ text: this.importText, placeholder: '粘贴 SKILL.md 全文（frontmatter 或 # 标题均可识别）' })
              .width('100%')
              .height(150)
              .backgroundColor($r('app.color.glass_bg_strong'))
              .fontColor($r('app.color.text_hi'))
              .placeholderColor($r('app.color.text_low'))
              .borderRadius(12)
              .onChange((v: string) => { this.importText = v; })
            Row({ space: 10 }) {
              Button('取消', { type: ButtonType.Capsule })
                .layoutWeight(1)
                .height(40)
                .fontSize(15)
                .fontColor($r('app.color.accent'))
                .backgroundColor($r('app.color.glass_bg_strong'))
                .onClick(() => { this.closeForms(); })
              Button('解析并导入', { type: ButtonType.Capsule })
                .layoutWeight(1)
                .height(40)
                .fontSize(15)
                .fontColor('#FFFFFF')
                .backgroundColor($r('app.color.accent'))
                .onClick(() => { this.doImport(); })
            }
            .width('100%')
          }
          .alignItems(HorizontalAlign.Start)
          .padding(14)
          .borderRadius(18)
          .backgroundColor($r('app.color.glass_bg'))
          .backdropBlur(ThemeTokens.BLUR_CARD)
          .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
          .shadow({ radius: 20, color: '#40000000', offsetY: 8 })
          .width('100%')
          .margin({ top: 12 })
        }

        // ===== 底部两按钮 =====
        Row({ space: 12 }) {
          Button('＋ 新建', { type: ButtonType.Capsule })
            .layoutWeight(1)
            .height(46)
            .fontSize(16)
            .fontColor(this.mode === 'create' ? $r('app.color.accent') : '#FFFFFF')
            .backgroundColor(this.mode === 'create' ? $r('app.color.glass_bg_strong') : $r('app.color.accent'))
            .onClick(() => {
              if (this.mode === 'create') { this.closeForms(); } else { this.openCreate(); }
            })
          Button('粘贴导入', { type: ButtonType.Capsule })
            .layoutWeight(1)
            .height(46)
            .fontSize(16)
            .fontColor(this.mode === 'import' ? $r('app.color.accent') : '#FFFFFF')
            .backgroundColor(this.mode === 'import' ? $r('app.color.glass_bg_strong') : $r('app.color.accent'))
            .onClick(() => {
              if (this.mode === 'import') { this.closeForms(); } else { this.openImport(); }
            })
        }
        .width('100%')
        .margin({ top: 12 })
      }
      .padding({ left: 18, right: 18, bottom: 24 })
      .width('100%')
      .height('100%')
    }
    .width('100%')
    .height('100%')
  }
}
