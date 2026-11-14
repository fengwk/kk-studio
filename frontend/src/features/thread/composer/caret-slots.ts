/** caret slot 的 DOM 标记；只有带该标记的节点才按 slot 处理。 */
export const CARET_SLOT_ATTRIBUTE = 'data-caret-slot'

/** 编辑器在 IME 组合期间的 DOM 标记：焦点状态机据此不触碰组合态。 */
export const COMPOSING_ATTRIBUTE = 'data-composing'

/** Slot 初始的零宽占位：逻辑字符长度为 0，也不产生可见空白。 */
export const CARET_SLOT_PLACEHOLDER = '\u200B'

export function isEditorComposing(editor: HTMLElement | null): boolean {
  return editor?.getAttribute(COMPOSING_ATTRIBUTE) === 'true'
}

export function isPillElement(node: Node | null): node is HTMLSpanElement {
  const partType =
    node?.nodeType === Node.ELEMENT_NODE
      ? (node as HTMLElement).getAttribute('data-part-type')
      : null
  return (
    node != null
    && node.nodeType === Node.ELEMENT_NODE
    && (node as HTMLElement).tagName.toLowerCase() === 'span'
    && (node as HTMLElement).hasAttribute('data-part-id')
    && (partType === 'attachment' || partType === 'resource')
  )
}

/** 是否为编辑器自己维护的 caret slot（DOM-only 边界，不属于 parts）。 */
export function isCaretSlot(node: Node | null | undefined): node is HTMLSpanElement {
  return (
    node != null
    && node.nodeType === Node.ELEMENT_NODE
    && (node as HTMLElement).tagName.toLowerCase() === 'span'
    && (node as HTMLElement).hasAttribute(CARET_SLOT_ATTRIBUTE)
  )
}

/** slot 内文本去掉哨兵占位：只剥除 slot 内明确标记的占位，不触碰用户文本。 */
export function stripSlotPlaceholder(text: string): string {
  const index = text.indexOf(CARET_SLOT_PLACEHOLDER)
  if (index < 0) {
    return text
  }
  return `${text.slice(0, index)}${text.slice(index + 1)}`
}

/**
 * Slot 的逻辑文本：去掉编辑器写入的占位，保留用户真正输入的字符。
 * 对非 slot 元素等同于 textContent 去占位。
 */
export function slotLogicalText(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) {
    return stripSlotPlaceholder(node.textContent ?? '')
  }
  if (node.nodeType !== Node.ELEMENT_NODE) {
    return ''
  }
  let text = ''
  for (const child of Array.from(node.childNodes)) {
    text += slotLogicalText(child)
  }
  return text
}

export function createCaretSlot(document: Document): HTMLSpanElement {
  const slot = document.createElement('span')
  slot.setAttribute(CARET_SLOT_ATTRIBUTE, 'true')
  slot.className = 'composer-caret-slot'
  slot.appendChild(document.createTextNode(CARET_SLOT_PLACEHOLDER))
  return slot
}

export function childIndexOf(node: Node): number {
  const parent = node.parentNode
  if (!parent) {
    return -1
  }
  return Array.prototype.indexOf.call(parent.childNodes, node)
}

export function mergeAdjacentTextNodes(root: HTMLElement): void {
  for (let index = 0; index < root.childNodes.length - 1; ) {
    const current = root.childNodes[index]
    const next = root.childNodes[index + 1]
    if (current?.nodeType === Node.TEXT_NODE && next?.nodeType === Node.TEXT_NODE) {
      const merged = root.ownerDocument.createTextNode(
        `${current.textContent ?? ''}${next.textContent ?? ''}`,
      )
      root.replaceChild(merged, current)
      root.removeChild(next)
      continue
    }
    index += 1
  }
  for (let index = root.childNodes.length - 1; index >= 0; index -= 1) {
    const node = root.childNodes[index]
    if (node?.nodeType === Node.TEXT_NODE && node.textContent === '') {
      root.removeChild(node)
    }
  }
}
