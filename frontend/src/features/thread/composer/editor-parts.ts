import {
  createPartId,
  partsKey,
  type ComposerPart,
  type ImageInputTier,
} from '@/features/thread/composer/composer-parts'
import {
  ensureCaretSlots,
  isCaretSlot,
  isPillElement,
  slotLogicalText,
} from '@/features/thread/composer/caret-space'

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

export type PillPart = Exclude<ComposerPart, { type: 'text' }>

/** 创建 pill span（contenteditable=false；携带 part identity 与引用属性）。 */
export function createPillElement(root: HTMLElement, part: PillPart): HTMLSpanElement {
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
