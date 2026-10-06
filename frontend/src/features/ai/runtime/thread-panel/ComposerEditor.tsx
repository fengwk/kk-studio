import {
  useEffect,
  useImperativeHandle,
  useRef,
  type ClipboardEvent,
  type DragEvent,
  type FormEvent,
  type KeyboardEvent,
  type Ref,
  type RefObject,
} from 'react'
import {
  extractPartsFromEditor,
  extractPartsKeyFromEditor,
  findAdjacentPill,
  insertPillsAtCaret,
  insertTextAtCaret,
  normalizeEditorDom,
  placeCaretAtEnd,
  renderPartsToEditor,
} from '@/features/ai/composer/composer-dom'
import {
  mergeTextParts,
  partsKey,
  removePartsByIds,
  type ComposerPart,
  type ImageInputTier,
} from '@/features/ai/composer/composer-parts'
import { useI18n } from '@/shared/i18n'
import '@/features/ai/runtime/thread-panel/composer-editor.css'

export interface ComposerEditorHandle {
  /**
   * 把受控 parts 对齐到 DOM 并返回 DOM 当前提取的 merged parts（编辑器未挂载时为 null）。
   * 供提交/预览前校验 DOM 与受控草稿是否一致。
   */
  syncDraft: () => ComposerPart[] | null
  /** 在光标处插入非文本 parts（attachment/resource pill），随后回流草稿。 */
  insertParts: (parts: ComposerPart[]) => void
  /**
   * 移除引用指定上传句柄的 attachment pill 并强制回流草稿（上传失败的 DOM 回滚）。
   * IME 组合期间延后到 compositionend，避免重建组合态 DOM。
   */
  removeAttachmentPills: (uploadIds: readonly string[]) => void
  /** 就地更新已渲染 attachment pill 的 imageTier，避免整树重建丢失光标与选区。 */
  updateAttachmentImageTier: (uploadIds: readonly string[], tier: ImageInputTier) => void
}

export interface ComposerEditorProps {
  /** 受控 ordered parts；外部同步（恢复/重放）在非组合态下重建 DOM。 */
  parts: ComposerPart[]
  /** 不可编辑时只保留草稿展示（contentEditable=false + aria-disabled）。 */
  disabled: boolean
  /** 草稿回流入口：DOM 提取结果或键盘整颗删除 pill 后的 parts。 */
  onPartsChange: (parts: ComposerPart[]) => void
  /** 编辑交互（输入/粘贴/拖放/pill 编辑/预览前对齐）通知：外层据此关闭覆盖层。 */
  onEditStart?: () => void
  /** 编辑器 mousedown：外层据此关闭底栏设置菜单（命令表由编辑行为本身关闭）。 */
  onEditorMouseDown?: () => void
  /** 编辑器未消费的按键（命令表导航、提交）；返回 true 表示已消费。 */
  onKeyDown?: (event: KeyboardEvent<HTMLDivElement>) => boolean
  /** 光标位于首/末行时的方向键历史回放导航；返回是否发生导航。 */
  onNavigateHistory?: (direction: 'previous' | 'next') => boolean
  /** 粘贴/拖放携带的文件；上传注册表与附件编排归外层所有。 */
  onFiles?: (files: File[]) => void
  /** 编辑器根节点，由外层的焦点状态机持有（聚焦重试、光标归位、全局 Escape）。 */
  editorRef: RefObject<HTMLDivElement | null>
  ref?: Ref<ComposerEditorHandle>
}

/**
 * ordered Pill Composer 的 contenteditable 编辑边界。
 *
 * 只负责编辑器自身能力：DOM 与 {@link ComposerPart} 的双向转换、IME 组合、
 * caret/selection、attachment/resource pill 节点交互、纯文本粘贴/拖放与
 * 历史回放渲染。上传注册表、草稿历史、命令表/工具栏与提交编排由外层
 * （{@link ThreadComposer}）持有，本组件不依赖任何业务 API 或 Thread 身份。
 */
export function ComposerEditor({
  ref,
  parts,
  disabled,
  onPartsChange,
  onEditStart,
  onEditorMouseDown,
  onKeyDown,
  onNavigateHistory,
  onFiles,
  editorRef,
}: ComposerEditorProps) {
  const { t } = useI18n()
  const isComposingRef = useRef(false)
  // 组合期间到达的上传失败：DOM pill 回滚延后到 compositionend，避免重建组合态 DOM。
  const pendingFailedUploadIdsRef = useRef<Set<string>>(new Set())

  /** 从当前 DOM 提取 parts 并回流（输入/粘贴/拖放/pill 操作后的统一入口）。 */
  function syncFromDom(force = false): ComposerPart[] | null {
    const el = editorRef.current
    if (!el) {
      return null
    }
    onEditStart?.()
    normalizeEditorDom(el)
    const next = mergeTextParts(extractPartsFromEditor(el))
    if (force || partsKey(next) !== partsKey(parts)) {
      onPartsChange(next)
    }
    return next
  }

  /** 移除引用指定 uploadId 的 attachment pill；返回是否真的移除了节点。 */
  function removePills(uploadIds: readonly string[]): boolean {
    const el = editorRef.current
    if (!el) {
      return false
    }
    let removed = false
    for (const uploadId of uploadIds) {
      const pills = el.querySelectorAll<HTMLElement>(
        `span[data-part-type="attachment"][data-upload-id="${uploadId}"]`,
      )
      pills.forEach((pill) => {
        pill.remove()
        removed = true
      })
    }
    return removed
  }

  /** DOM 与 props 对齐（外部同步或初始渲染）；重建时保留焦点与光标。 */
  useEffect(() => {
    if (isComposingRef.current) {
      return
    }
    const el = editorRef.current
    if (!el) {
      return
    }
    // parts 状态保持相邻 text 已合并的规范形态；比较/渲染前先规范化，
    // 避免「重建 -> 提取合并 -> 键不一致」的循环。
    // partsKey 含 attachment 的客户端 uploadId：同名但不同上传会触发重建，
    // 确保 DOM pill 的 partId 永远来自当前 parts（不留过期 partId）。
    const expectedParts = mergeTextParts(parts)
    const expected = partsKey(expectedParts)
    if (extractPartsKeyFromEditor(el) !== expected) {
      const hadFocus = document.activeElement === el
      renderPartsToEditor(el, expectedParts)
      if (hadFocus) {
        placeCaretAtEnd(el)
      }
    }
  }, [editorRef, parts])

  function handleCompositionStart() {
    isComposingRef.current = true
  }

  function handleCompositionEnd() {
    isComposingRef.current = false
    const hadFailedUploads = pendingFailedUploadIdsRef.current.size > 0
    if (hadFailedUploads) {
      removePills(Array.from(pendingFailedUploadIdsRef.current))
    }
    pendingFailedUploadIdsRef.current.clear()
    syncFromDom(hadFailedUploads)
  }

  /**
   * 输入事件统一回流草稿。Enter / Shift+Enter 的换行由浏览器默认行为产生
   * （div/br），在此经 normalizeEditorDom 收敛为纯文本 '\n'，不额外拦截按键。
   */
  function handleInput(event: FormEvent<HTMLDivElement>) {
    if (isComposingRef.current) {
      return
    }
    const native = event.nativeEvent as InputEvent
    if (native?.isComposing) {
      return
    }
    syncFromDom()
  }

  /** 编辑器消费 pill 删除与历史回放按键，其余按键交给外层（命令表/提交）。 */
  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (disabled) {
      // contentEditable=false 仍会收到按键：不可编辑时不得触发提交、命令表或历史回放。
      return
    }
    if (event.nativeEvent.isComposing || event.keyCode === 229 || isComposingRef.current) {
      return
    }
    if (event.key === 'Backspace' || event.key === 'Delete') {
      const el = editorRef.current
      const pill = el ? findAdjacentPill(el, event.key === 'Backspace' ? 'before' : 'after') : null
      if (pill) {
        // 整颗 pill 删除：直接移除 DOM 节点并回流 parts（保持光标稳定）。
        event.preventDefault()
        event.stopPropagation()
        const partId = pill.getAttribute('data-part-id')
        pill.remove()
        if (partId) {
          const next = mergeTextParts(removePartsByIds(parts, new Set([partId])))
          if (partsKey(next) !== partsKey(parts)) {
            onPartsChange(next)
            return
          }
        }
        syncFromDom()
        return
      }
    }
    if (onKeyDown?.(event)) {
      return
    }
    // 方向键到达首/末行边界时才接管为历史回放；否则交给 contenteditable 移动光标。
    if (
      (event.key === 'ArrowDown' || event.key === 'ArrowUp')
      && !event.shiftKey
      && !event.ctrlKey
      && !event.metaKey
      && !event.altKey
    ) {
      const el = editorRef.current
      const direction = event.key === 'ArrowUp' ? 'previous' : 'next'
      if (
        el
        && canNavigateHistoryFromCaret(el, direction)
        && onNavigateHistory?.(direction)
      ) {
        event.preventDefault()
        event.stopPropagation()
      }
    }
  }

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
        const normalized = rawText.replace(/\r\n|\r/g, '\n')
        const el = editorRef.current
        if (el) {
          insertTextAtCaret(el, normalized)
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
        insertTextAtCaret(el, text.replace(/\r\n|\r/g, '\n'))
        syncFromDom()
      }
    }
  }

  useImperativeHandle(ref, () => ({
    syncDraft: () => syncFromDom(false),
    insertParts: (pillParts) => {
      const el = editorRef.current
      if (!el) {
        return
      }
      // 文件插入发生在当前光标处（粘贴/拖放/选择），而不是追加到末尾；
      // 插入后重新提取回流 parts，保持 DOM 与状态同构。
      insertPillsAtCaret(el, pillParts)
      syncFromDom()
    },
    removeAttachmentPills: (uploadIds) => {
      if (isComposingRef.current) {
        for (const uploadId of uploadIds) {
          pendingFailedUploadIdsRef.current.add(uploadId)
        }
        return
      }
      // 上传可能在最新 parts effect 生效前失败；以当前 DOM 强制覆盖受控草稿，
      // 避免旧闭包误判相等后把失败 pill 重新插回。
      if (removePills(uploadIds)) {
        syncFromDom(true)
      }
    },
    updateAttachmentImageTier: (uploadIds, tier) => {
      const el = editorRef.current
      if (!el) {
        return
      }
      for (const uploadId of uploadIds) {
        const pills = el.querySelectorAll<HTMLElement>(
          `span[data-part-type="attachment"][data-upload-id="${uploadId}"]`,
        )
        pills.forEach((pill) => {
          pill.dataset.imageTier = tier
        })
      }
    },
  }))

  const draftIsEmpty = parts.every(
    (part) => part.type === 'text' && part.text.trim() === '',
  )

  return (
    <div
      ref={editorRef}
      className="composer-editor"
      contentEditable={!disabled}
      role="textbox"
      aria-multiline="true"
      aria-label={t('ai.runtime.composer.ariaLabel')}
      aria-disabled={disabled}
      data-placeholder={t('ai.runtime.composer.placeholder')}
      data-placeholder-visible={draftIsEmpty}
      onCompositionStart={handleCompositionStart}
      onCompositionEnd={handleCompositionEnd}
      onInput={handleInput}
      onKeyDown={handleKeyDown}
      onPaste={handlePaste}
      onDrop={handleDrop}
      onMouseDown={() => onEditorMouseDown?.()}
    />
  )
}

function clipboardFiles(clipboard: DataTransfer | null): File[] {
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

/**
 * 光标是否处于可触发历史回放的位置：collapsed selection 位于编辑器内，
 * 且当前行到编辑器起点/终点之间没有换行（首行 ArrowUp / 末行 ArrowDown）。
 */
function canNavigateHistoryFromCaret(
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
