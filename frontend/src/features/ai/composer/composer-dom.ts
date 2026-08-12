import {
  createPartId,
  partsKey,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'

/**
 * contenteditable editor 的最小 DOM parts 契约。
 *
 * Editor 的 DOM 只包含两类节点：纯文本节点（text part）与
 * `contenteditable=false` 的 pill span（attachment part）。Pill 携带
 * `data-part-id` 与 `data-upload-id`（以及展示用的 `data-filename`）。
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
  return (
    node != null
    && node.nodeType === Node.ELEMENT_NODE
    && (node as HTMLElement).tagName.toLowerCase() === 'span'
    && (node as HTMLElement).hasAttribute('data-part-id')
    && (node as HTMLElement).hasAttribute('data-upload-id')
  )
}

/** 从 DOM 提取 ordered parts：text 节点 -> text part，pill span -> attachment part。 */
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
      const uploadId = node.getAttribute('data-upload-id') ?? ''
      const filename = node.getAttribute('data-filename') ?? ''
      if (uploadId) {
        parts.push({
          type: 'attachment',
          partId: node.getAttribute('data-part-id') ?? createPartId(),
          uploadId,
          filename,
        })
      }
    }
  }
  return parts
}

export function extractPartsKeyFromEditor(root: HTMLElement): string {
  return partsKey(extractPartsFromEditor(root))
}

/** 创建 pill span（contenteditable=false；携带 partId/uploadId/filename 属性）。 */
function createPillElement(root: HTMLElement, part: ComposerPart & { type: 'attachment' }): HTMLSpanElement {
  const pill = root.ownerDocument.createElement('span')
  pill.className = 'composer-pill'
  pill.contentEditable = 'false'
  pill.dataset.partId = part.partId
  pill.dataset.uploadId = part.uploadId
  pill.dataset.filename = part.filename
  pill.textContent = `[${part.filename}](upload)`
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

/**
 * 将浏览器可能产生的 div/p/br 结构折叠回纯文本 + pill 形态：
 * 块边界与 <br> 都变成 '\n' 文本节点，其他元素解包保留文本；嵌套的
 * pill 会按文档顺序保留。相邻块边界只产生一个 '\n'（Chrome div 模型）。
 *
 * 已处于规范形态（仅文本节点与 pill）时不改动 DOM——保存光标。
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
  const document = root.ownerDocument
  const normalized: Node[] = []
  // 编辑器起点视为已处于边界：首个块不会产生前导空行。
  let lastWasBoundary = true

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
    lastWasBoundary = true
  }

  const walk = (node: Node, atEnd: boolean): void => {
    if (node.nodeType === Node.TEXT_NODE) {
      pushText(node.textContent ?? '')
      return
    }
    if (node.nodeType !== Node.ELEMENT_NODE) {
      return
    }
    if (isPillElement(node)) {
      normalized.push(node)
      lastWasBoundary = false
      return
    }
    const element = node as HTMLElement
    const tag = element.tagName.toLowerCase()
    if (tag === 'br') {
      if (!atEnd) {
        pushBoundary()
      }
      return
    }
    const block = BLOCK_TAGS.has(tag)
    if (block && !atEnd) {
      pushBoundary()
    }
    const children = Array.from(node.childNodes)
    children.forEach((child, index) => {
      walk(child, atEnd && index === children.length - 1)
    })
    if (block && !atEnd) {
      pushBoundary()
    }
  }

  const children = Array.from(root.childNodes)
  children.forEach((child, index) => {
    walk(child, index === children.length - 1)
  })

  root.replaceChildren(...normalized)
  mergeAdjacentTextNodes(root)
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
    root.appendChild(root.ownerDocument.createTextNode(text))
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
 * 在光标处插入 attachment pills（文件粘贴/拖放/选择后调用），光标移到最后一颗
 * pill 之后；光标不在 editor 内时追加到末尾。插入后由调用方重新提取并回传 parts。
 */
export function insertPillsAtCaret(root: HTMLElement, parts: ComposerPart[]): void {
  const selection = root.ownerDocument.getSelection()
  const pills = parts
    .filter((part): part is ComposerPart & { type: 'attachment' } => part.type === 'attachment')
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

/** 把光标放到编辑器末尾（外部重建 DOM 后恢复焦点用）。 */
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
