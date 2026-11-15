import type { ClipboardEvent, DragEvent } from 'react'
import {
  insertTextAtCaret,
  selectionTouchesCaretSlot,
  serializeSelectionText,
} from '@/features/thread/composer/composer-dom'
import {
  clipboardFiles,
  normalizePastedText,
} from '@/features/thread/runtime/thread-panel/composer-editor-dom'

/**
 * 编辑器的剪贴板与拖放边界。
 *
 * - 携带文件的粘贴/拖放交给上传注册表（由调用方编排附件），不写入正文；
 * - 纯文本一律插入为规范化换行，绕开浏览器自带的富文本/HTML 粘贴；
 * - 复制/剪切在选区触及 caret slot 时接管，slot 占位不得进入剪贴板，
 *   其余情况保留浏览器原生剪贴板与撤销行为。
 *
 * 不可编辑时全部 no-op：contentEditable=false 仍会收到这些事件。
 */
export function useComposerClipboard({
  disabled,
  editorRef,
  onFiles,
  syncFromDom,
}: {
  disabled: boolean
  editorRef: React.RefObject<HTMLDivElement | null>
  onFiles?: (files: File[]) => void
  syncFromDom: (force?: boolean) => void
}) {
  function handlePaste(event: ClipboardEvent<HTMLDivElement>) {
    if (disabled) {
      // contentEditable=false 仍会收到 paste：不可编辑时不得编排附件或改写草稿。
      return
    }
    const files = clipboardFiles(event.clipboardData)
    if (files.length > 0) {
      event.preventDefault()
      onFiles?.(files)
      return
    }
    const clipboard = event.clipboardData
    if (clipboard) {
      event.preventDefault()
      const rawText = clipboard.getData('text/plain')
      if (rawText) {
        const el = editorRef.current
        if (el) {
          insertTextAtCaret(el, normalizePastedText(rawText))
          syncFromDom()
        }
      }
    }
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    if (disabled) {
      // contentEditable=false 仍会收到 drop：不可编辑时不得编排附件或改写草稿。
      return
    }
    const files = Array.from(event.dataTransfer?.files ?? [])
    if (files.length > 0) {
      event.preventDefault()
      onFiles?.(files)
      return
    }
    const text = event.dataTransfer?.getData('text/plain') ?? ''
    if (text) {
      event.preventDefault()
      const el = editorRef.current
      if (el) {
        insertTextAtCaret(el, normalizePastedText(text))
        syncFromDom()
      }
    }
  }

  /**
   * 复制/剪切序列化：slot 占位不进剪贴板。只在选区真的触及占位时接管，
   * 其余情况保留浏览器原生剪贴板与撤销行为。返回是否接管了默认行为。
   */
  function writeSelectionToClipboard(event: ClipboardEvent<HTMLDivElement>): boolean {
    const el = editorRef.current
    if (!el || !selectionTouchesCaretSlot(el)) {
      return false
    }
    const text = serializeSelectionText(el)
    if (text === null) {
      return false
    }
    event.preventDefault()
    event.clipboardData?.setData('text/plain', text)
    return true
  }

  function handleCopy(event: ClipboardEvent<HTMLDivElement>) {
    writeSelectionToClipboard(event)
  }

  /** 接管剪切时浏览器不再删除选区，需要自己删除并回流 parts。 */
  function handleCut(event: ClipboardEvent<HTMLDivElement>) {
    if (!writeSelectionToClipboard(event)) {
      return
    }
    window.getSelection()?.deleteFromDocument()
    syncFromDom(true)
  }

  return { handlePaste, handleDrop, handleCopy, handleCut }
}
