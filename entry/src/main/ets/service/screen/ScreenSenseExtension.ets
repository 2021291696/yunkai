import AccessibilityExtensionAbility, { AccessibilityEvent } from '@ohos.application.AccessibilityExtensionAbility';
import { hilog } from '@kit.PerformanceAnalysisKit';
import process from '@ohos.process';

const TAG = 'yunkai';
const DOMAIN = 0x0000;

/**
 * 屏幕感知扩展服务（M3 Phase 0 spike 最小骨架）。
 * 本骨架只回答三个裁决点，业务能力（读屏工具/手势基元/状态机）在后续 Task 落地：
 * ① onConnect/onAccessibilityEvent 是否触发——英文 V13 文档将回调标为 API 12 废弃，实测为准；
 * ② 本服务与 UIAbility 是否同进程（hilog pid 对比 EntryAbility）——决定 P1 ScreenBridge 走内存单例还是 commonEvent；
 * ③ context.getWindowRootElement() 能否读到活动窗口文本树。
 */
export default class ScreenSenseExtension extends AccessibilityExtensionAbility {
  onConnect(): void {
    hilog.info(DOMAIN, TAG, 'scr: onConnect pid=%{public}d', process.pid);
    this.probeRoot();
  }

  onDisconnect(): void {
    hilog.info(DOMAIN, TAG, 'scr: onDisconnect pid=%{public}d', process.pid);
  }

  onAccessibilityEvent(event: AccessibilityEvent): void {
    hilog.info(DOMAIN, TAG, 'scr: event type=%{public}d', event.eventType);
  }

  private async probeRoot(): Promise<void> {
    try {
      const root = await this.context.getWindowRootElement();
      const text: string = String(await root.attributeValue('text') ?? '');
      const bundle: string = String(await root.attributeValue('bundleName') ?? '');
      const actions: string[] = await root.actionNames();
      hilog.info(DOMAIN, TAG,
        'scr: probe root text=%{public}s bundle=%{public}s actions=%{public}s',
        text, bundle, actions.join(','));
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'scr: probe root fail %{public}s', JSON.stringify(e));
    }
  }
}
