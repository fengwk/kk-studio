import {
  createPartId,
  partsKey,
  type ComposerPart,
  type ImageInputTier,
} from '@/features/ai/composer/composer-parts'

/**
 * contenteditable editor 的最小 DOM parts 契约。
 *
 * Editor 的 DOM 只包含两类节点：纯文本节点（text part）与
 * `contenteditable=false` 的 pill span（attachment/resource part）。Pill 携带
 * `data-part-id`、`data-part-type` 与对应引用字段。
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

/** 从 DOM 提取 ordered parts：text 节点 -> text part，pill span -> attachment/resource part。 */
export function extractPartsFromEditor(root: HTMLElement): ComposerPart[] {
  const parts: ComposerPart[] = []
  for (const node of Array.from(root.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.textContent ?? ''
      const previous = parts[parts.length - 1]
      if (previous?.type === 'text') {
        parts[parts.length - 1] = { ...previous, text: previous.text + text }
      } else if (text.length > 0) {
        parts.push({ type: 'text', partId: createPartId(), text })
      }
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
}

function offsetToNormalizedPoint(
  root: HTMLElement,
  offset: number,
): { node: Node; offset: number } {
  const children = Array.from(root.childNodes)
  if (children.length === 0) {
    return { node: root, offset: 0 }
  }
  let remaining = offset
  for (let i = 0; i < children.length; i++) {
    const child = children[i]
    if (child.nodeType === Node.TEXT_NODE) {
      const len = child.textContent?.length ?? 0
      if (remaining < len) {
        return { node: child, offset: remaining }
      }
      if (remaining === len) {
        return { node: child, offset: len }
      }
      remaining -= len
    } else if (isPillElement(child)) {
      if (remaining === 0) {
        return { node: root, offset: i }
      }
      if (remaining === 1) {
        const next = children[i + 1]
        if (next && next.nodeType === Node.TEXT_NODE) {
          return { node: next, offset: 0 }
        }
        return { node: root, offset: i + 1 }
      }
      remaining -= 1
    }
  }
  const last = children[children.length - 1]
  if (last && last.nodeType === Node.TEXT_NODE) {
    return { node: last, offset: last.textContent?.length ?? 0 }
  }
  return { node: root, offset: children.length }
}

/**
 * 将浏览器可能产生的 div/p/br 结构折叠回纯文本 + pill 形态：
 * 块边界与 <br> 都变成 '\n' 文本节点，其他元素解包保留文本；嵌套的
 * pill 会按文档顺序保留。相邻块边界只产生一个 '\n'（Chrome div 模型）。
 *
 * 重建过程通过规范化字符偏移映射保留活动的 Selection / Caret。
 * 已处于规范形态（仅文本节点与 pill）时不改动 DOM——天然保留光标。
 */
export function normalizeEditorDom(root: HTMLElement): void {
  let canonical = true
  for (const node of Array.from(root.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      if (node.textContent === '') {
        canonical = false
      }
      continue
    }
    if (isPillElement(node)) {
      continue
    }
    canonical = false
    break
  }
  if (canonical) {
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

  const walk = (node: Node, atEnd: boolean): void => {
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.textContent ?? ''
      if (rangeToPreserve) {
        if (savedStart === null && node === rangeToPreserve.startContainer) {
          savedStart = currentNormalizedLength + Math.max(0, Math.min(rangeToPreserve.startOffset, text.length))
        }
        if (savedEnd === null && node === rangeToPreserve.endContainer) {
          savedEnd = currentNormalizedLength + Math.max(0, Math.min(rangeToPreserve.endOffset, text.length))
        }
      }
      pushText(text)
      return
    }
    if (node.nodeType !== Node.ELEMENT_NODE) {
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
      walk(child, atEnd && index === children.length - 1)
      checkPoint(node, index + 1)
    })
    if (block && !atEnd) {
      pushBoundary()
    }
  }

  const children = Array.from(root.childNodes)
  checkPoint(root, 0)
  children.forEach((child, index) => {
    walk(child, index === children.length - 1)
    checkPoint(root, index + 1)
  })

  root.replaceChildren(...normalized)
  mergeAdjacentTextNodes(root)

  if (rangeToPreserve && savedStart !== null) {
    const finalStart = savedStart
    const finalEnd = rangeToPreserve.collapsed ? savedStart : (savedEnd ?? savedStart)
    const startPoint = offsetToNormalizedPoint(root, finalStart)
    const endPoint = rangeToPreserve.collapsed ? startPoint : offsetToNormalizedPoint(root, finalEnd)
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

/**
 * 在光标处插入非文本 pills，光标移到最后一颗
 * pill 之后；光标不在 editor 内时追加到末尾。插入后由调用方重新提取并回传 parts。
 */
export function insertPillsAtCaret(root: HTMLElement, parts: ComposerPart[]): void {
  const selection = root.ownerDocument.getSelection()
  const pills = parts
    .filter((part): part is PillPart => part.type !== 'text')
    .map((part) => createPillElement(root, part))
  if (pills.length === 0) {
    return
  }
  if (!selection || selection.rangeCount === 0) {
    for (const pill of pills) {
      root.appendChild(pill)
    }
    return
  }
  const range = selection.getRangeAt(0)
  if (root.contains(range.commonAncestorContainer) === false) {
    for (const pill of pills) {
      root.appendChild(pill)
    }
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
  const nextRange = root.ownerDocument.createRange()
  nextRange.setStartAfter(lastNode)
  nextRange.collapse(true)
  selection.removeAllRanges()
  selection.addRange(nextRange)
}

/** 把光标放到编辑器末尾，并确保外部重建后的末尾内容可见。 */
export function placeCaretAtEnd(root: HTMLElement): void {
  const selection = root.ownerDocument.getSelection()
  if (!selection) {
    return
  }
  const range = root.ownerDocument.createRange()
  range.selectNodeContents(root)
  range.collapse(false)
  selection.removeAllRanges()
  selection.addRange(range)
  root.scrollTop = root.scrollHeight
}

/**
 * 返回紧邻光标的 pill（Backspace 检查 caret 之前的兄弟，Delete 检查之后）。
 * 仅处理 collapsed selection；跨选区删除交给浏览器默认行为（input 事件回流）。
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
  const container = range.startContainer
  const offset = range.startOffset
  const candidate =
    container.nodeType === Node.TEXT_NODE
      ? (direction === 'before'
          ? (offset === 0 ? container.previousSibling : null)
          : (offset === (container.textContent?.length ?? 0) ? container.nextSibling : null))
      : (direction === 'before'
          ? (offset > 0 ? container.childNodes[offset - 1] : null)
          : (offset < container.childNodes.length ? container.childNodes[offset] : null))
  if (candidate == null || !isPillElement(candidate)) {
    return null
  }
  return candidate as HTMLElement
}
