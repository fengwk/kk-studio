/**
 * 剪贴板里的文件：直接 files 优先，否则从 items 里取 file 项。
 * 用于粘贴图片/文件时交给上传注册表，而不是当成文本插入。
 */
export function clipboardFiles(clipboard: DataTransfer | null): File[] {
  if (!clipboard) {
    return []
  }
  const direct = Array.from(clipboard.files ?? [])
  if (direct.length > 0) {
    return direct
  }
  return Array.from(clipboard.items ?? [])
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter((file): file is File => file != null)
}

/** 粘贴/拖放的纯文本一律以 '\n' 作为换行，避免 '\r\n' 变成两条逻辑行。 */
export function normalizePastedText(raw: string): string {
  return raw.replace(/\r\n|\r/g, '\n')
}

/**
 * 光标是否处于可触发历史回放的位置：collapsed selection 位于编辑器内，
 * 且当前行到编辑器起点/终点之间没有换行（首行 ArrowUp / 末行 ArrowDown）。
 */
export function canNavigateHistoryFromCaret(
  root: HTMLElement,
  direction: 'previous' | 'next',
): boolean {
  const selection = root.ownerDocument.getSelection()
  if (!selection || selection.rangeCount === 0) {
    return false
  }
  const caret = selection.getRangeAt(0)
  if (
    !caret.collapsed
    || !root.contains(caret.commonAncestorContainer)
  ) {
    return false
  }
  const range = root.ownerDocument.createRange()
  range.selectNodeContents(root)
  if (direction === 'previous') {
    range.setEnd(caret.startContainer, caret.startOffset)
  } else {
    range.setStart(caret.endContainer, caret.endOffset)
  }
  return !range.toString().includes('\n')
}
