/**
 * 全局键盘处理的统一优先级守卫。
 *
 * 各全局/面板级 keydown handler 必须遵循以下优先级，避免低优先级作用域
 * 抢走本应属于高优先级作用域的按键：
 *
 *   Modal/Lightbox/alertdialog > interaction/menu > focused Event/Canvas > focused Pane Composer
 *
 * - `hasBlockingModal`：Modal/alertdialog/lightbox 拥有自己的 Escape 语义；
 *   任何全局 handler（Composer 焦点恢复、Canvas 快捷键、AppShell 导航、
 *   interaction panel）都必须在存在更高优先级 overlay 时让路。
 * - `isEditableKeyboardTarget`：输入控件（INPUT/TEXTAREA/SELECT/contenteditable）
 *   中的按键交给输入自身，全局快捷键一律不拦截。
 */
const BLOCKING_MODAL_SELECTOR =
  '.modal-backdrop, [aria-modal="true"], [role="alertdialog"], .resource-media-lightbox'

export function hasBlockingModal(root: ParentNode = document): boolean {
  return root.querySelector(BLOCKING_MODAL_SELECTOR) != null
}

export function isInsideBlockingModal(element: Element): boolean {
  return element.closest(BLOCKING_MODAL_SELECTOR) != null
}

/**
 * 当前全局按键是否应让位于模态层：存在 blocking overlay，且目标元素不在其中时
 * 返回 true（面板自身的 handler 若位于 overlay 内部仍可继续处理）。
 */
export function shouldDeferToBlockingModal(target: Element | null): boolean {
  return hasBlockingModal() && (target == null || !isInsideBlockingModal(target))
}

/** 输入目标（INPUT/TEXTAREA/SELECT/contenteditable）中的按键不触发全局快捷键。 */
export function isEditableKeyboardTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false
  }
  if (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
    return true
  }
  return target.isContentEditable === true
}
