/**
 * 全局键盘处理的最小辅助：模态层优先于全局 Escape。
 *
 * Modal/alertdialog/lightbox 拥有自己的 Escape 语义；任何全局 handler（Composer
 * 焦点恢复、Canvas 快捷键、interaction panel）都必须在存在更高优先级 overlay 时让路。
 */
const BLOCKING_OVERLAY_SELECTOR =
  '.modal-backdrop, [aria-modal="true"], [role="alertdialog"], .resource-media-lightbox'

export function hasBlockingOverlay(root: ParentNode = document): boolean {
  return root.querySelector(BLOCKING_OVERLAY_SELECTOR) != null
}

export function isInsideBlockingOverlay(element: Element): boolean {
  return element.closest(BLOCKING_OVERLAY_SELECTOR) != null
}

/**
 * 当前全局 Escape 是否应让位于模态层：存在 blocking overlay，且目标元素不在其中时
 * 返回 true（面板自身的 handler 若位于 overlay 内部仍可继续处理）。
 */
export function shouldDeferToBlockingOverlay(target: Element | null): boolean {
  return hasBlockingOverlay() && (target == null || !isInsideBlockingOverlay(target))
}
