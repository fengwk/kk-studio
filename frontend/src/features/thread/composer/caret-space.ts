import {
  CARET_SLOT_PLACEHOLDER,
  createCaretSlot,
  isCaretSlot,
  isPillElement,
  mergeAdjacentTextNodes,
  slotLogicalText,
  stripSlotPlaceholder,
} from '@/features/thread/composer/caret-slots'

// editor 侧统一从 caret-space 取节点判定与逻辑空间工具，避免两个入口各导一半。
export { isCaretSlot, isPillElement, mergeAdjacentTextNodes, slotLogicalText, stripSlotPlaceholder }

/**
 * 逻辑字符空间（slot 占位长度为 0、pill 为 1 个原子单位）里的长度、光标定位与选区
 * 序列化。DOM 节点自身的形态维护在 caret-slots，parts 转换在 editor-parts。
 */

/** 把 slot 的子树提升到父级（逻辑文本不变），用于离开 pill 边界的 slot。 */
export function unwrapCaretSlot(root: HTMLElement, slot: HTMLElement): void {
  const parent = slot.parentNode
  if (!parent) {
    slot.remove()
    return
  }
  // 提升会移动节点并使其中的 Range 折叠；先按逻辑偏移记住光标再恢复。
  const caret = logicalCaretOffset(root)
  for (const child of Array.from(slot.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      child.textContent = stripSlotPlaceholder(child.textContent ?? '')
    }
    parent.insertBefore(child, slot)
  }
  slot.remove()
  if (caret !== null) {
    placeCaretAtLogicalOffset(root, caret)
  }
}

/**
 * 维护 pill 相邻的可编辑 caret 边界：
 *
 * - 不再紧邻 pill 的 slot 提升为普通文本，避免占位残留成隐藏空白；
 * - 缺少可编辑相邻位置的 pill 补 slot。已有文本节点本身就是可编辑位置，
 *   因此只在边界为空、相邻的是 pill 或 slot 时插入。
 *
 * 插入、恢复草稿、规范化与 pill 删除都走这一处，保证边界形态唯一。
 */
export function ensureCaretSlots(root: HTMLElement): void {
  for (const slot of Array.from(root.children).filter(isCaretSlot)) {
    const previous = slot.previousSibling
    const next = slot.nextSibling
    const adjacentToPill =
      (previous != null && isPillElement(previous)) || (next != null && isPillElement(next))
    if (!adjacentToPill) {
      unwrapCaretSlot(root, slot)
    }
  }
  for (const node of Array.from(root.childNodes)) {
    if (!isPillElement(node)) {
      continue
    }
    const previous = node.previousSibling
    if (previous == null || (previous.nodeType !== Node.TEXT_NODE && !isCaretSlot(previous))) {
      node.parentNode?.insertBefore(createCaretSlot(root.ownerDocument), node)
    }
    const next = node.nextSibling
    if (next == null || (next.nodeType !== Node.TEXT_NODE && !isCaretSlot(next))) {
      node.parentNode?.insertBefore(createCaretSlot(root.ownerDocument), next)
    }
  }
}

/** 把全部 slot 展开为普通文本节点（插入/粘贴前的扁平化，避免 pill 嵌入 slot）。 */
export function flattenCaretSlots(root: HTMLElement): void {
  for (const slot of Array.from(root.children).filter(isCaretSlot)) {
    const parent = slot.parentNode
    if (!parent) {
      slot.remove()
      continue
    }
    for (const child of Array.from(slot.childNodes)) {
      if (child.nodeType === Node.TEXT_NODE) {
        child.textContent = stripSlotPlaceholder(child.textContent ?? '')
      }
      parent.insertBefore(child, slot)
    }
    slot.remove()
  }
  mergeAdjacentTextNodes(root)
}

/** 节点在逻辑字符空间（占位长度为 0、pill 为 1 个原子单位）中的长度。 */
export function logicalNodeLength(node: Node): number {
  if (node.nodeType === Node.TEXT_NODE) {
    // slot 内的文本去掉哨兵占位后计数。
    return isCaretSlot(node.parentNode)
      ? slotLogicalText(node).length
      : (node.textContent?.length ?? 0)
  }
  if (node.nodeType === Node.ELEMENT_NODE) {
    const element = node as HTMLElement
    if (isPillElement(element)) {
      return 1
    }
    if (isCaretSlot(element)) {
      return slotLogicalText(element).length
    }
  }
  // 文档片段（Range.cloneContents）与含子节点的其他节点按子节点累加。
  let total = 0
  for (const child of Array.from(node.childNodes)) {
    total += logicalNodeLength(child)
  }
  return total
}

/** 逻辑字符空间中前 offset 个实际字符内的字符数（跳过 slot 占位）。 */
export function logicalPrefixLength(text: string, offset: number): number {
  const limit = Math.max(0, Math.min(offset, text.length))
  let logical = 0
  for (let index = 0; index < limit; index += 1) {
    if (text[index] !== CARET_SLOT_PLACEHOLDER) {
      logical += 1
    }
  }
  return logical
}

/** 光标定位到 slot 的文本节点内（而不是 pill 内或裸父节点间隙）。 */
export function slotCaretPoint(slot: HTMLElement): { node: Node; offset: number } {
  const text = slot.firstChild
  if (text?.nodeType === Node.TEXT_NODE) {
    return { node: text, offset: text.textContent?.length ?? 0 }
  }
  return { node: slot, offset: 0 }
}

/**
 * 逻辑字符偏移 -> DOM caret 点。邻近 pill 的边界优先落入 slot 的文本节点，
 * 保证输入法拿到的始终是可编辑文本位置。
 */
export function caretPointAt(root: HTMLElement, offset: number): { node: Node; offset: number } {
  const children = Array.from(root.childNodes)
  if (children.length === 0) {
    return { node: root, offset: 0 }
  }
  let remaining = offset
  for (let index = 0; index < children.length; index += 1) {
    const child = children[index]
    if (isCaretSlot(child)) {
      if (remaining <= 0) {
        return slotCaretPoint(child)
      }
      continue
    }
    if (child.nodeType === Node.TEXT_NODE) {
      const length = child.textContent?.length ?? 0
      if (remaining < length) {
        return { node: child, offset: remaining }
      }
      if (remaining === length) {
        const next = children[index + 1]
        if (next != null && isCaretSlot(next)) {
          return slotCaretPoint(next)
        }
        return { node: child, offset: length }
      }
      remaining -= length
      continue
    }
    if (isPillElement(child)) {
      if (remaining === 0) {
        const previous = children[index - 1]
        if (previous != null && isCaretSlot(previous)) {
          return slotCaretPoint(previous)
        }
        return { node: root, offset: index }
      }
      if (remaining === 1) {
        const next = children[index + 1]
        if (next != null && isCaretSlot(next)) {
          return slotCaretPoint(next)
        }
        if (next != null && next.nodeType === Node.TEXT_NODE) {
          return { node: next, offset: 0 }
        }
        return { node: root, offset: index + 1 }
      }
      remaining -= 1
      continue
    }
    const length = logicalNodeLength(child)
    if (remaining < length) {
      return { node: child, offset: remaining }
    }
    remaining -= length
  }
  const last = children[children.length - 1]
  if (last != null) {
    if (isCaretSlot(last)) {
      return slotCaretPoint(last)
    }
    if (last.nodeType === Node.TEXT_NODE) {
      return { node: last, offset: last.textContent?.length ?? 0 }
    }
  }
  return { node: root, offset: children.length }
}

export function applyCaretPoint(root: HTMLElement, point: { node: Node; offset: number }): void {
  const selection = root.ownerDocument.getSelection()
  if (!selection) {
    return
  }
  const range = root.ownerDocument.createRange()
  try {
    range.setStart(point.node, point.offset)
  } catch {
    return
  }
  range.collapse(true)
  selection.removeAllRanges()
  selection.addRange(range)
}

/** 编辑器当前内容的逻辑字符长度（slot 占位为 0）。 */
export function editorLogicalLength(root: HTMLElement): number {
  return logicalNodeLength(root)
}

/** 逻辑字符空间中的光标位置；无 collapsed caret 或光标不在编辑器内时为 null。 */
export function logicalCaretOffset(root: HTMLElement): number | null {
  const selection = root.ownerDocument.getSelection()
  if (!selection || selection.rangeCount === 0) {
    return null
  }
  const caret = selection.getRangeAt(0)
  if (!caret.collapsed || !root.contains(caret.startContainer)) {
    return null
  }
  const before = root.ownerDocument.createRange()
  before.selectNodeContents(root)
  try {
    before.setEnd(caret.startContainer, caret.startOffset)
  } catch {
    return null
  }
  return logicalNodeLength(before.cloneContents())
}

/** 把光标放到逻辑字符偏移处（邻近 pill 的边界落入 slot）。 */
export function placeCaretAtLogicalOffset(root: HTMLElement, offset: number): void {
  applyCaretPoint(root, caretPointAt(root, offset))
}

export function logicalTextOf(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) {
    return node.textContent ?? ''
  }
  if (node.nodeType === Node.ELEMENT_NODE) {
    const element = node as HTMLElement
    if (isPillElement(element)) {
      return element.textContent ?? ''
    }
    if (isCaretSlot(element)) {
      return slotLogicalText(element)
    }
  }
  let text = ''
  for (const child of Array.from(node.childNodes)) {
    text += logicalTextOf(child)
  }
  return text
}

/**
 * 选区序列化文本：slot 占位不进剪贴板，pill 与其可见标签一致。
 * 无 selection 或选区不在编辑器内时返回 null。
 */
export function serializeSelectionText(root: HTMLElement): string | null {
  const selection = root.ownerDocument.getSelection()
  if (!selection || selection.rangeCount === 0) {
    return null
  }
  const range = selection.getRangeAt(0)
  if (!root.contains(range.commonAncestorContainer)) {
    return null
  }
  return logicalTextOf(range.cloneContents())
}

/**
 * 选区是否触及 caret slot 占位。否——浏览器默认复制/剪切已正确，保留原生撤销边界；
 * 是——必须用 {@link serializeSelectionText} 的序列化结果替换默认行为。
 */
export function selectionTouchesCaretSlot(root: HTMLElement): boolean {
  const selection = root.ownerDocument.getSelection()
  if (!selection || selection.rangeCount === 0) {
    return false
  }
  const range = selection.getRangeAt(0)
  if (!root.contains(range.commonAncestorContainer)) {
    return false
  }
  return logicalTextOf(range.cloneContents()) !== range.toString()
}
