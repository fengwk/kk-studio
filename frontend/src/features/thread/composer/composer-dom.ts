import {
  createPartId,
  partsKey,
  type ComposerPart,
  type ImageInputTier,
} from '@/features/thread/composer/composer-parts'

/**
 * contenteditable editor 的最小 DOM parts 契约。
 *
 * Editor 的 DOM 只包含三类节点：纯文本节点（text part）、`contenteditable=false`
 * 的 pill span（attachment/resource part），以及仅存在于 DOM 的 caret slot。
 * Pill 携带 `data-part-id`、`data-part-type` 与对应引用字段；caret slot 携带
 * `data-caret-slot`，初始时内部只有一个 U+200B 占位。
 *
 * Caret slot 存在的理由：不可编辑 pill 的相邻边界若没有任何可编辑文本位置，
 * 输入法会把组合输入送进 pill 内部而卡住首个字符。Slot 为这类边界提供原生编辑
 * 需要的文本位置，同时完全留在 parts 之外——不成为 part、不进剪贴板与草稿历史，
 * 逻辑字符长度为 0。已有文本节点的边界天然可编辑，不重复放置 slot。
 *
 * 这些是纯函数 DOM 辅助；组件负责事件驱动下的调用时机与状态回传。
 */

const BLOCK_TAGS = new Set([
  'div',
  'p',
  'li',
  'section',
  'pre',
  'blockquote',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
])

/** caret slot 的 DOM 标记；只有带该标记的节点才按 slot 处理。 */
const CARET_SLOT_ATTRIBUTE = 'data-caret-slot'

/** 编辑器在 IME 组合期间的 DOM 标记：焦点状态机据此不触碰组合态。 */
export const COMPOSING_ATTRIBUTE = 'data-composing'

/** Slot 初始的零宽占位：逻辑字符长度为 0，也不产生可见空白。 */
const CARET_SLOT_PLACEHOLDER = '\u200B'

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
function stripSlotPlaceholder(text: string): string {
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
function slotLogicalText(node: Node): string {
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

function createCaretSlot(document: Document): HTMLSpanElement {
  const slot = document.createElement('span')
  slot.setAttribute(CARET_SLOT_ATTRIBUTE, 'true')
  slot.className = 'composer-caret-slot'
  slot.appendChild(document.createTextNode(CARET_SLOT_PLACEHOLDER))
  return slot
}

function childIndexOf(node: Node): number {
  const parent = node.parentNode
  if (!parent) {
    return -1
  }
  return Array.prototype.indexOf.call(parent.childNodes, node)
}

/** 把 slot 的子树提升到父级（逻辑文本不变），用于离开 pill 边界的 slot。 */
function unwrapCaretSlot(root: HTMLElement, slot: HTMLElement): void {
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
function flattenCaretSlots(root: HTMLElement): void {
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

/** 从 DOM 提取 ordered parts：text 节点 -> text part，pill span -> attachment/resource part。 */
export function extractPartsFromEditor(root: HTMLElement): ComposerPart[] {
  const parts: ComposerPart[] = []
  const pushText = (text: string): void => {
    if (text.length === 0) {
      return
    }
    const previous = parts[parts.length - 1]
    if (previous?.type === 'text') {
      parts[parts.length - 1] = { ...previous, text: previous.text + text }
      return
    }
    parts.push({ type: 'text', partId: createPartId(), text })
  }
  for (const node of Array.from(root.childNodes)) {
    if (isCaretSlot(node)) {
      // Slot 不是 part：只取其中真正输入的文本，占位被丢弃。
      pushText(slotLogicalText(node))
      continue
    }
    if (node.nodeType === Node.TEXT_NODE) {
      pushText(node.textContent ?? '')
      continue
    }
    if (isPillElement(node)) {
      const partId = node.getAttribute('data-part-id') ?? createPartId()
      const rawTier = node.getAttribute('data-image-tier')
      const imageTier = parseImageTier(rawTier)
      if (node.getAttribute('data-part-type') === 'attachment') {
        const uploadId = node.getAttribute('data-upload-id') ?? ''
        const filename = node.getAttribute('data-filename') ?? ''
        if (!uploadId) {
          continue
        }
        parts.push({
          type: 'attachment',
          partId,
          uploadId,
          filename,
          ...(imageTier ? { imageTier } : {}),
        })
        continue
      }
      const blobId = node.getAttribute('data-blob-id') ?? ''
      const name = node.getAttribute('data-name') ?? ''
      if (blobId && name) {
        parts.push({
          type: 'resource',
          partId,
          blobId,
          name,
          ...(node.hasAttribute('data-preview')
            ? { preview: node.getAttribute('data-preview') ?? '' }
            : {}),
          ...(imageTier ? { imageTier } : {}),
        })
      }
    }
  }
  return parts
}

function parseImageTier(value: string | null | undefined): ImageInputTier | undefined {
  if (value === '720P' || value === '1080P' || value === 'ORIGINAL') {
    return value
  }
  return undefined
}

export function extractPartsKeyFromEditor(root: HTMLElement): string {
  return partsKey(extractPartsFromEditor(root))
}

type PillPart = Exclude<ComposerPart, { type: 'text' }>

/** 创建 pill span（contenteditable=false；携带 part identity 与引用属性）。 */
function createPillElement(root: HTMLElement, part: PillPart): HTMLSpanElement {
  const pill = root.ownerDocument.createElement('span')
  pill.className = 'composer-pill'
  pill.contentEditable = 'false'
  pill.dataset.partId = part.partId
  pill.dataset.partType = part.type
  if (part.type === 'attachment') {
    pill.dataset.uploadId = part.uploadId
    pill.dataset.filename = part.filename
    if (part.imageTier) {
      pill.dataset.imageTier = part.imageTier
    }
    pill.textContent = `[${part.filename}]`
  } else {
    pill.dataset.blobId = part.blobId
    pill.dataset.name = part.name
    if (part.preview !== undefined) {
      pill.dataset.preview = part.preview
    }
    if (part.imageTier) {
      pill.dataset.imageTier = part.imageTier
    }
    pill.textContent = `[${part.name}]`
  }
  return pill
}

/** 用 parts 重建 editor 内容（外部同步时使用；会丢失光标位置）。 */
export function renderPartsToEditor(root: HTMLElement, parts: ComposerPart[]): void {
  root.replaceChildren()
  for (const part of parts) {
    if (part.type === 'text') {
      root.appendChild(root.ownerDocument.createTextNode(part.text))
      continue
    }
    root.appendChild(createPillElement(root, part))
  }
  ensureCaretSlots(root)
}

/** 节点在逻辑字符空间（占位长度为 0、pill 为 1 个原子单位）中的长度。 */
function logicalNodeLength(node: Node): number {
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
function logicalPrefixLength(text: string, offset: number): number {
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
function slotCaretPoint(slot: HTMLElement): { node: Node; offset: number } {
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
function caretPointAt(root: HTMLElement, offset: number): { node: Node; offset: number } {
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

function applyCaretPoint(root: HTMLElement, point: { node: Node; offset: number }): void {
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
function editorLogicalLength(root: HTMLElement): number {
  return logicalNodeLength(root)
}

/** 逻辑字符空间中的光标位置；无 collapsed caret 或光标不在编辑器内时为 null。 */
function logicalCaretOffset(root: HTMLElement): number | null {
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
function placeCaretAtLogicalOffset(root: HTMLElement, offset: number): void {
  applyCaretPoint(root, caretPointAt(root, offset))
}

function logicalTextOf(node: Node): string {
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

/**
 * 判断编辑器 DOM 是否已是规范形态：非空文本节点、pill，以及紧邻 pill 的 slot。
 * caret slot 可以承载用户文本（IME 组合与键入不加重建），因此不因非空而失格。
 * 规范形态下不做任何 DOM 改动——保留文本节点身份与 selection。
 */
function isCanonicalEditorDom(root: HTMLElement): boolean {
  const children = Array.from(root.childNodes)
  for (let index = 0; index < children.length; index += 1) {
    const node = children[index]
    if (node.nodeType === Node.TEXT_NODE) {
      if (node.textContent === '') {
        return false
      }
      continue
    }
    if (isPillElement(node)) {
      const previous = children[index - 1]
      const next = children[index + 1]
      if (previous == null || (previous.nodeType !== Node.TEXT_NODE && !isCaretSlot(previous))) {
        return false
      }
      if (next == null || (next.nodeType !== Node.TEXT_NODE && !isCaretSlot(next))) {
        return false
      }
      continue
    }
    if (isCaretSlot(node)) {
      const previous = children[index - 1]
      const next = children[index + 1]
      const adjacentToPill =
        (previous != null && isPillElement(previous)) || (next != null && isPillElement(next))
      if (!adjacentToPill) {
        return false
      }
      continue
    }
    return false
  }
  return true
}

/**
 * 将浏览器可能产生的 div/p/br 结构折叠回纯文本 + pill 形态：
 * 块边界与 <br> 都变成 '\n' 文本节点，其他元素解包保留文本；嵌套的
 * pill 会按文档顺序保留。相邻块边界只产生一个 '\n'（Chrome div 模型）。
 *
 * caret slot 在重建中展开为其逻辑文本：只剩占位的 slot 直接消失，
 * 边界由紧随其后的 ensureCaretSlots 重新补齐。
 *
 * 重建过程通过规范化字符偏移映射保留活动的 Selection / Caret。
 * 已处于规范形态时不改动 DOM——天然保留光标。
 */
export function normalizeEditorDom(root: HTMLElement): void {
  if (isCanonicalEditorDom(root)) {
    return
  }

  const selection = root.ownerDocument.getSelection()
  let rangeToPreserve: {
    startContainer: Node
    startOffset: number
    endContainer: Node
    endOffset: number
    collapsed: boolean
  } | null = null

  if (selection && selection.rangeCount > 0) {
    const activeRange = selection.getRangeAt(0)
    if (
      root.contains(activeRange.startContainer)
      && root.contains(activeRange.endContainer)
    ) {
      rangeToPreserve = {
        startContainer: activeRange.startContainer,
        startOffset: activeRange.startOffset,
        endContainer: activeRange.endContainer,
        endOffset: activeRange.endOffset,
        collapsed: activeRange.collapsed,
      }
    }
  }

  const document = root.ownerDocument
  const normalized: Node[] = []
  // 编辑器起点视为已处于边界：首个块不会产生前导空行。
  let lastWasBoundary = true
  let currentNormalizedLength = 0

  let savedStart: number | null = null
  let savedEnd: number | null = null

  const checkPoint = (container: Node, offset: number): void => {
    if (!rangeToPreserve) {
      return
    }
    if (
      savedStart === null
      && container === rangeToPreserve.startContainer
      && offset === rangeToPreserve.startOffset
    ) {
      savedStart = currentNormalizedLength
    }
    if (
      savedEnd === null
      && container === rangeToPreserve.endContainer
      && offset === rangeToPreserve.endOffset
    ) {
      savedEnd = currentNormalizedLength
    }
  }

  const pushText = (text: string): void => {
    if (!text) {
      return
    }
    const previous = normalized[normalized.length - 1]
    if (previous?.nodeType === Node.TEXT_NODE) {
      previous.textContent = `${previous.textContent ?? ''}${text}`
    } else {
      normalized.push(document.createTextNode(text))
    }
    currentNormalizedLength += text.length
    lastWasBoundary = false
  }

  const pushBoundary = (): void => {
    if (lastWasBoundary) {
      return
    }
    const previous = normalized[normalized.length - 1]
    if (previous?.nodeType === Node.TEXT_NODE) {
      previous.textContent = `${previous.textContent ?? ''}\n`
    } else {
      normalized.push(document.createTextNode('\n'))
    }
    currentNormalizedLength += 1
    lastWasBoundary = true
  }

  const walk = (node: Node, atEnd: boolean, inSlot: boolean): void => {
    if (node.nodeType === Node.TEXT_NODE) {
      const raw = node.textContent ?? ''
      // slot 内的哨兵占位在逻辑字符空间中不存在，映射光标时按占位后的偏移计数。
      const text = inSlot ? stripSlotPlaceholder(raw) : raw
      if (rangeToPreserve) {
        if (savedStart === null && node === rangeToPreserve.startContainer) {
          const offset = inSlot
            ? logicalPrefixLength(raw, rangeToPreserve.startOffset)
            : Math.max(0, Math.min(rangeToPreserve.startOffset, text.length))
          savedStart = currentNormalizedLength + offset
        }
        if (savedEnd === null && node === rangeToPreserve.endContainer) {
          const offset = inSlot
            ? logicalPrefixLength(raw, rangeToPreserve.endOffset)
            : Math.max(0, Math.min(rangeToPreserve.endOffset, text.length))
          savedEnd = currentNormalizedLength + offset
        }
      }
      pushText(text)
      return
    }
    if (node.nodeType !== Node.ELEMENT_NODE) {
      return
    }
    if (isCaretSlot(node)) {
      checkPoint(node, 0)
      const children = Array.from(node.childNodes)
      children.forEach((child, index) => {
        walk(child, atEnd && index === children.length - 1, true)
      })
      checkPoint(node, 1)
      return
    }
    if (isPillElement(node)) {
      checkPoint(node, 0)
      normalized.push(node)
      lastWasBoundary = false
      currentNormalizedLength += 1
      checkPoint(node, 1)
      return
    }
    const element = node as HTMLElement
    const tag = element.tagName.toLowerCase()
    if (tag === 'br') {
      checkPoint(node, 0)
      if (!atEnd) {
        pushBoundary()
      }
      checkPoint(node, 1)
      return
    }
    const block = BLOCK_TAGS.has(tag)
    if (block && !atEnd) {
      pushBoundary()
    }
    const children = Array.from(node.childNodes)
    checkPoint(node, 0)
    children.forEach((child, index) => {
      walk(child, atEnd && index === children.length - 1, inSlot)
      checkPoint(node, index + 1)
    })
    if (block && !atEnd) {
      pushBoundary()
    }
  }

  const children = Array.from(root.childNodes)
  checkPoint(root, 0)
  children.forEach((child, index) => {
    walk(child, index === children.length - 1, false)
    checkPoint(root, index + 1)
  })

  root.replaceChildren(...normalized)
  mergeAdjacentTextNodes(root)
  ensureCaretSlots(root)

  if (rangeToPreserve && savedStart !== null) {
    const finalStart = savedStart
    const finalEnd = rangeToPreserve.collapsed ? savedStart : (savedEnd ?? savedStart)
    const startPoint = caretPointAt(root, finalStart)
    const endPoint = rangeToPreserve.collapsed ? startPoint : caretPointAt(root, finalEnd)
    try {
      const newRange = root.ownerDocument.createRange()
      newRange.setStart(startPoint.node, startPoint.offset)
      if (rangeToPreserve.collapsed) {
        newRange.collapse(true)
      } else {
        newRange.setEnd(endPoint.node, endPoint.offset)
      }
      selection?.removeAllRanges()
      selection?.addRange(newRange)
    } catch {
      // 容错：个别浏览器对特定节点 Range 约束异常时不阻断规范化
    }
  }
}

function mergeAdjacentTextNodes(root: HTMLElement): void {
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

/** 在光标处插入纯文本（保留 selection）；不可编辑时不修改 DOM。 */
export function insertTextAtCaret(root: HTMLElement, text: string): void {
  const selection = root.ownerDocument.getSelection()
  if (!selection || selection.rangeCount === 0) {
    const node = root.ownerDocument.createTextNode(text)
    root.appendChild(node)
    ensureCaretSlots(root)
    const range = root.ownerDocument.createRange()
    range.setStartAfter(node)
    range.collapse(true)
    selection?.removeAllRanges()
    selection?.addRange(range)
    return
  }
  const range = selection.getRangeAt(0)
  if (root.contains(range.commonAncestorContainer) === false) {
    return
  }
  range.deleteContents()
  const node = root.ownerDocument.createTextNode(text)
  range.insertNode(node)
  range.setStartAfter(node)
  range.collapse(true)
  selection.removeAllRanges()
  selection.addRange(range)
}

/** 把光标放到 node 之后的可编辑位置（优先 slot 内的文本节点）。 */
function placeCaretAfterNode(root: HTMLElement, node: Node): void {
  const next = node.nextSibling
  if (next != null && isCaretSlot(next)) {
    applyCaretPoint(root, slotCaretPoint(next))
    return
  }
  if (next != null && next.nodeType === Node.TEXT_NODE) {
    applyCaretPoint(root, { node: next, offset: 0 })
    return
  }
  const parent = node.parentNode
  if (parent != null) {
    applyCaretPoint(root, { node: parent, offset: childIndexOf(node) + 1 })
  }
}

/**
 * 在光标处插入非文本 pills，光标移入最后一颗 pill 之后的可编辑边界；
 * 光标不在 editor 内时追加到末尾。插入前先把 slot 展开为普通文本，
 * 避免 pill 嵌在 slot 内部而脱离 parts 提取。插入后由调用方重新提取并回传 parts。
 */
export function insertPillsAtCaret(root: HTMLElement, parts: ComposerPart[]): void {
  const selection = root.ownerDocument.getSelection()
  const pills = parts
    .filter((part): part is PillPart => part.type !== 'text')
    .map((part) => createPillElement(root, part))
  if (pills.length === 0) {
    return
  }
  const caretOffset = logicalCaretOffset(root)
  flattenCaretSlots(root)
  if (caretOffset !== null) {
    placeCaretAtLogicalOffset(root, Math.min(caretOffset, editorLogicalLength(root)))
  }
  const hasSelection = selection != null && selection.rangeCount > 0
  if (!hasSelection || caretOffset === null) {
    for (const pill of pills) {
      root.appendChild(pill)
    }
    ensureCaretSlots(root)
    placeCaretAfterNode(root, pills[pills.length - 1])
    return
  }
  const range = selection.getRangeAt(0)
  if (root.contains(range.commonAncestorContainer) === false) {
    for (const pill of pills) {
      root.appendChild(pill)
    }
    ensureCaretSlots(root)
    placeCaretAfterNode(root, pills[pills.length - 1])
    return
  }
  range.deleteContents()
  const anchor = root.ownerDocument.createTextNode('')
  range.insertNode(anchor)
  let lastNode: Node = anchor
  for (const pill of pills) {
    anchor.parentNode?.insertBefore(pill, anchor)
    lastNode = pill
  }
  anchor.remove()
  ensureCaretSlots(root)
  placeCaretAfterNode(root, lastNode)
}

/** 把光标放到编辑器末尾，并确保外部重建后的末尾内容可见。 */
export function placeCaretAtEnd(root: HTMLElement): void {
  const selection = root.ownerDocument.getSelection()
  if (!selection) {
    return
  }
  ensureCaretSlots(root)
  applyCaretPoint(root, caretPointAt(root, editorLogicalLength(root)))
  root.scrollTop = root.scrollHeight
}

/**
 * 返回紧邻光标的 pill（Backspace 检查 caret 之前的兄弟，Delete 检查之后）。
 *
 * 判断在逻辑字符空间进行：caret slot 对用户透明（长度为 0），因此光标停在
 * slot 里等同于停在其边界；跨选区删除交给浏览器默认行为（input 事件回流）。
 */
export function findAdjacentPill(
  root: HTMLElement,
  direction: 'before' | 'after',
): HTMLElement | null {
  const selection = root.ownerDocument.getSelection()
  if (!selection || selection.rangeCount === 0) {
    return null
  }
  const range = selection.getRangeAt(0)
  if (!range.collapsed || !root.contains(range.commonAncestorContainer)) {
    return null
  }
  const caret = logicalCaretOffset(root)
  if (caret === null) {
    return null
  }
  let boundary = 0
  for (const child of Array.from(root.childNodes)) {
    const start = boundary
    boundary += logicalNodeLength(child)
    if (!isPillElement(child)) {
      continue
    }
    if (direction === 'before' && boundary === caret) {
      return child
    }
    if (direction === 'after' && start === caret) {
      return child
    }
  }
  return null
}
