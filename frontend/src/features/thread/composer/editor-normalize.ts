import {
  createPillElement,
  type PillPart,
} from '@/features/thread/composer/editor-parts'
import {
  applyCaretPoint,
  caretPointAt,
  editorLogicalLength,
  ensureCaretSlots,
  flattenCaretSlots,
  isCaretSlot,
  isPillElement,
  logicalCaretOffset,
  logicalNodeLength,
  logicalPrefixLength,
  mergeAdjacentTextNodes,
  placeCaretAtLogicalOffset,
  slotCaretPoint,
  stripSlotPlaceholder,
} from '@/features/thread/composer/caret-space'
import { childIndexOf } from '@/features/thread/composer/caret-slots'
import type { ComposerPart } from '@/features/thread/composer/composer-parts'

/** 规范化时识别为块边界的标签：它们在折叠后各产生一个 '\n'。 */
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
