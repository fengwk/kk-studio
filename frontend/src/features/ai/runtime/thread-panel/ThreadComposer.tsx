import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type ClipboardEvent,
  type DragEvent,
  type FormEvent,
  type KeyboardEvent,
} from 'react'
import { ArrowUp, Plus } from 'lucide-react'
import {
  ThreadCommandPalette,
} from '@/features/ai/runtime/thread-panel/ThreadCommandPalette'
import {
  firstEnabledCommandIndex,
  stepEnabledCommandIndex,
  useFilteredThreadCommands,
} from '@/features/ai/runtime/thread-panel/thread-command-navigation'
import { THREAD_COMMANDS, type ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import { AttachmentStrip } from '@/features/ai/composer/attachment-strip'
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
  slashQueryOf,
  trimMessageParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  canSubmitParts,
  partForUpload,
  removePartsForUpload,
  uploadOccurrence,
  useAttachmentUploads,
  type AttachmentUpload,
  type HashFile,
  type StorageService,
} from '@/features/ai/composer'
import { useComposerMessageHistory } from '@/features/ai/runtime/thread-panel/useComposerMessageHistory'
import { hasBlockingModal } from '@/shared/ui/blocking-overlay'
import { useI18n } from '@/shared/i18n'

const EMPTY_USER_MESSAGES: readonly string[] = []

/**
 * 共享的 Attachment Pill Composer（OpenCode 风格）。
 *
 * 原生 contenteditable editor + `contenteditable=false` 附件 pill；draft 是
 * ordered {@link ComposerPart}（TEXT/ATTACHMENT），DOM pill 携带
 * `data-part-id` 与 `data-upload-id`。只有文件粘贴/拖放/选择会创建 pill，
 * typed '@filename' 始终是文本。
 *
 * 上传注册表（strip）由组件内部持有；通过可注入的 storageService/hashFile
 * 适配（Canvas 后续可复用同一契约，无需依赖本组件之外的 AI 状态）。
 */
export function ThreadComposer({
  parts,
  pending,
  disabled,
  onPartsChange,
  onHistoryPartsChange = onPartsChange,
  onSubmit,
  onCommand,
  commands = THREAD_COMMANDS,
  historicalUserMessages = EMPTY_USER_MESSAGES,
  queuedUserMessages = EMPTY_USER_MESSAGES,
  storageService,
  hashFile,
  focusOnEscape = false,
  active = true,
}: {
  parts: ComposerPart[]
  pending: boolean
  disabled: boolean
  onPartsChange: (parts: ComposerPart[]) => void
  onHistoryPartsChange?: (parts: ComposerPart[]) => void
  /**
   * 提交载荷（payload, localDraft）：
   * - payload：attachment parts 的 uploadId 已解析为服务端 upload 句柄，
   *   用于构建 command batch / HTTP 发送；
   * - localDraft：客户端 localId 的草稿快照（trim 后），用于失败恢复、
   *   replay 与 FirstSendRecovery——恢复比对只命中本地 id 草稿。
   */
  onSubmit: (payload: ComposerPart[], localDraft: ComposerPart[]) => void
  onCommand: (command: ThreadCommand) => void
  commands?: ThreadCommand[]
  historicalUserMessages?: readonly string[]
  queuedUserMessages?: readonly string[]
  storageService?: StorageService
  hashFile?: HashFile
  /** 当前交互作用域是否允许全局 Escape 把焦点恢复到此 Composer。 */
  focusOnEscape?: boolean
  /** false 时由同一 Composer 区域的 interaction panel 接管；组件保持挂载以保留上传状态。 */
  active?: boolean
}) {
  const { t } = useI18n()
  const editorRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const focusTimerRef = useRef<number | null>(null)
  const restoreFocusRef = useRef(false)
  const wasPendingRef = useRef(false)
  // 提交时的本地草稿快照（客户端 localId parts，trim 后）：发送失败恢复
  // （相同 partsKey）时重新挂载上传条目；其余任何 parts 变化都视为上一轮
  // 提交已 settle（条目隐藏，等待结果）。与提交 payload（server uploadId）
  // 分开保存——恢复比对只可能命中本地草稿。
  const submittedDraftRef = useRef<ComposerPart[] | null>(null)
  const settleTransitionRef = useRef(false)
  const submittingRef = useRef(false)
  const {
    uploads,
    addFiles,
    retryUpload,
    releaseUpload,
    markDetached,
  } = useAttachmentUploads({ storageService, hashFile })
  const {
    changeDraft,
    navigate: navigateMessageHistory,
  } = useComposerMessageHistory({
    parts,
    historicalUserMessages,
    queuedUserMessages,
    onPartsChange,
    onHistoryPartsChange,
  })

  const slashQuery = slashQueryOf(parts)
  const slashMode = slashQuery != null
  const [plusMenuOpen, setPlusMenuOpen] = useState(false)
  const paletteMode = plusMenuOpen ? 'menu' : slashMode ? 'slash' : null
  const paletteOpen = paletteMode != null
  const query = paletteMode === 'slash' ? slashQuery ?? '' : ''
  const filteredCommands = useFilteredThreadCommands(query, commands)
  const [activeIndex, setActiveIndex] = useState(0)
  const draftIsEmpty = parts.every(
    (part) => part.type === 'text' && part.text.trim() === '',
  )

  // 进行中的 HTTP 变更不能阻塞连续提交；附件未全部 ready 时也不能发送。
  const canSend = useMemo(
    () => !disabled && !slashMode && canSubmitParts(parts, uploads),
    [disabled, parts, slashMode, uploads],
  )

  const clearFocusTimer = useCallback(() => {
    if (focusTimerRef.current === null) {
      return
    }
    window.clearTimeout(focusTimerRef.current)
    focusTimerRef.current = null
  }, [])

  const focusComposer = useCallback((forceCaretAtEnd = false) => {
    clearFocusTimer()
    const scheduleFocus = (attempt: number, delay: number) => {
      focusTimerRef.current = window.setTimeout(() => {
        focusTimerRef.current = null
        tryFocus(attempt)
      }, delay)
    }
    const tryFocus = (attempt: number) => {
      const el = editorRef.current
      if (!el || el.getAttribute('contenteditable') !== 'true') {
        if (attempt < 5) {
          scheduleFocus(attempt + 1, 16)
        }
        return
      }
      if (document.activeElement !== el) {
        el.focus({ preventScroll: true })
      }
      const selection = el.ownerDocument.getSelection()
      if (
        document.activeElement === el
        && (
          forceCaretAtEnd
          || !selection
          || selection.rangeCount === 0
          || !el.contains(selection.getRangeAt(0).commonAncestorContainer)
        )
      ) {
        placeCaretAtEnd(el)
      }
      if (attempt < 5 && document.activeElement !== el) {
        scheduleFocus(attempt + 1, 16)
      }
    }
    scheduleFocus(0, 0)
  }, [clearFocusTimer])

  const closeCommandPalette = useCallback((forceCaretAtEnd = false) => {
    setPlusMenuOpen(false)
    if (paletteMode === 'slash') {
      changeDraft([])
    }
    focusComposer(forceCaretAtEnd)
  }, [changeDraft, focusComposer, paletteMode])

  useEffect(() => {
    if (!active) {
      restoreFocusRef.current = true
      clearFocusTimer()
      return
    }
    if (restoreFocusRef.current && !disabled) {
      restoreFocusRef.current = false
      focusComposer(true)
    }
  }, [active, clearFocusTimer, disabled, focusComposer])

  useEffect(() => {
    if (!focusOnEscape || !active) {
      return
    }
    const handleEscape = (event: globalThis.KeyboardEvent) => {
      if (
        event.key !== 'Escape'
        || event.defaultPrevented
        || event.isComposing
        || event.keyCode === 229
      ) {
        return
      }
      // Modal/alertdialog/lightbox 保留自己的 Escape 语义；关闭后再次按 Escape 才回到 Composer。
      if (hasBlockingModal()) {
        return
      }
      if (editorRef.current?.getAttribute('contenteditable') !== 'true') {
        return
      }
      event.preventDefault()
      if (paletteOpen) {
        closeCommandPalette(true)
        return
      }
      focusComposer(true)
    }
    window.addEventListener('keydown', handleEscape)
    return () => window.removeEventListener('keydown', handleEscape)
  }, [active, closeCommandPalette, focusComposer, focusOnEscape, paletteOpen])

  useEffect(() => {
    if (!paletteOpen) {
      return
    }
    setActiveIndex(firstEnabledCommandIndex(filteredCommands))
  }, [paletteOpen, query, filteredCommands])

  // 发送完成后（pending true -> false），继续在 composer 中键入。
  useEffect(() => {
    if (wasPendingRef.current && !pending && !disabled) {
      focusComposer()
    }
    wasPendingRef.current = pending
  }, [pending, disabled, focusComposer])

  useEffect(() => () => clearFocusTimer(), [clearFocusTimer])

  /** DOM 与 props 对齐（外部同步或初始渲染）；重建时保留焦点与光标。 */
  useEffect(() => {
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
  }, [parts])

  /** 释放未被任何 parts 引用且未 detached 的条目（pill 移除/提交 settle 后调用）。 */
  function releaseUnreferenced(
    currentUploads: AttachmentUpload[],
    currentParts: ComposerPart[],
    release: (localId: string) => void,
  ) {
    for (const upload of currentUploads) {
      if (upload.detached) {
        continue
      }
      if (uploadOccurrence(upload, currentParts) === 0) {
        release(upload.localId)
      }
    }
  }

  /** 提交快照引用的上传条目 localId 集合（本地草稿直接按 localId 匹配）。 */
  function submittedUploadIds(submitted: ComposerPart[], currentUploads: AttachmentUpload[]): Set<string> {
    return new Set(
      currentUploads
        .filter((upload) => uploadOccurrence(upload, submitted) > 0)
        .map((upload) => upload.localId),
    )
  }

  /** 释放/挂起：pill 移除（occurrence 0）与提交 settle/恢复的生命周期。 */
  useEffect(() => {
    const submitted = submittedDraftRef.current
    if (submitted == null) {
      releaseUnreferenced(uploads, parts, releaseUpload)
      return
    }
    if (partsKey(parts) === partsKey(submitted)) {
      if (settleTransitionRef.current) {
        // 发送失败的恢复：重新挂载全部上传条目。
        settleTransitionRef.current = false
        submittedDraftRef.current = null
        markDetached(submittedUploadIds(submitted, uploads), false)
      }
      return
    }
    // 提交后的清空或编辑：条目隐藏等待结果；恢复前绝不 DELETE
    // （submittedDraftRef 保留本地草稿快照，供失败恢复比对）。
    if (!settleTransitionRef.current) {
      settleTransitionRef.current = true
      markDetached(submittedUploadIds(submitted, uploads), true)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [parts])

  /** pending true -> false：上一轮提交结果确定；draft 未恢复 => 发送成功，释放挂起条目。 */
  useEffect(() => {
    if (submittingRef.current && !pending) {
      submittingRef.current = false
      const submitted = submittedDraftRef.current
      if (submitted != null && partsKey(parts) !== partsKey(submitted)) {
        submittedDraftRef.current = null
        settleTransitionRef.current = false
        for (const upload of uploads) {
          if (upload.detached) {
            releaseUpload(upload.localId)
          }
        }
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pending])

  /** 从当前 DOM 提取 parts 并回传（输入/粘贴/删除后统一入口）。 */
  function syncFromDom() {
    const el = editorRef.current
    if (!el) {
      return
    }
    if (plusMenuOpen) {
      setPlusMenuOpen(false)
    }
    normalizeEditorDom(el)
    const next = mergeTextParts(extractPartsFromEditor(el))
    if (partsKey(next) !== partsKey(parts)) {
      changeDraft(next)
    }
  }

  function addFilesToDraft(files: File[]) {
    if (disabled || files.length === 0) {
      return
    }
    const added = addFiles(files)
    const pillParts = added.filter((upload) => upload.status !== 'error').map(partForUpload)
    if (pillParts.length === 0) {
      return
    }
    const el = editorRef.current
    if (!el) {
      changeDraft([...parts, ...pillParts])
      return
    }
    // 文件插入发生在当前光标处（粘贴/拖放/选择），而不是追加到末尾；
    // 插入后重新提取回流 parts，保持 DOM 与状态同构。
    insertPillsAtCaret(el, pillParts)
    syncFromDom()
  }

  /** 提交时把 attachment parts 的客户端 localId 解析为服务端 upload 句柄。 */
  function resolveUploadIds(currentParts: ComposerPart[]): ComposerPart[] {
    return currentParts.map((part) => {
      if (part.type !== 'attachment') {
        return part
      }
      const record = uploads.find((upload) => upload.localId === part.uploadId)
      if (record?.uploadId) {
        return { ...part, uploadId: record.uploadId }
      }
      return part
    })
  }

  function handleSubmit() {
    if (!canSend) {
      return
    }
    setPlusMenuOpen(false)
    // 上一轮已 settle 的 detached 残留在此释放（它们的消息已确定不再回滚）。
    for (const upload of uploads) {
      if (upload.detached) {
        releaseUpload(upload.localId)
      }
    }
    // 草稿始终引用客户端 localId；提交 payload 在序列化前解析为服务端 upload
    // 句柄（避免「上传完成异步改写 parts」与用户编辑竞态）。恢复快照必须保存
    // 本地草稿（trim 后），与 payload 分开——恢复比对只命中本地 id 草稿。
    const resolved = resolveUploadIds(parts)
    const localDraft = trimMessageParts(parts)
    submittingRef.current = true
    submittedDraftRef.current = localDraft
    onSubmit(resolved, localDraft)
  }

  function handleSelect(command: ThreadCommand) {
    if (command.disabled) {
      return
    }
    const consumeSlashCommand = paletteMode === 'slash'
    setPlusMenuOpen(false)
    if (consumeSlashCommand) {
      changeDraft([])
    }
    if (command.id === 'upload') {
      fileInputRef.current?.click()
      return
    }
    focusComposer()
    onCommand(command)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
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
            changeDraft(next)
            return
          }
        }
        syncFromDom()
        return
      }
    }
    if (event.key === 'Escape' && paletteOpen) {
      event.preventDefault()
      event.stopPropagation()
      closeCommandPalette()
      return
    }
    if (paletteOpen && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault()
      event.stopPropagation()
      const delta = event.key === 'ArrowDown' ? 1 : -1
      setActiveIndex((current) => stepEnabledCommandIndex(filteredCommands, current, delta))
      return
    }
    if (
      !paletteOpen
      && (event.key === 'ArrowDown' || event.key === 'ArrowUp')
      && !event.shiftKey
      && !event.ctrlKey
      && !event.metaKey
      && !event.altKey
    ) {
      const el = editorRef.current
      const direction = event.key === 'ArrowUp' ? 'previous' : 'next'
      if (
        el
        && canNavigateMessageHistoryFromCaret(el, direction)
        && navigateMessageHistory(direction)
      ) {
        event.preventDefault()
        event.stopPropagation()
        return
      }
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      if (paletteOpen) {
        event.preventDefault()
        const command = filteredCommands[activeIndex]
        if (command && !command.disabled) {
          handleSelect(command)
        } else {
          const fallback = filteredCommands.find((item) => !item.disabled)
          if (fallback) {
            handleSelect(fallback)
          }
        }
        return
      }
      event.preventDefault()
      handleSubmit()
      focusComposer()
    }
  }

  /** Enter 归一化为纯文本 '\n'；IME 组合期间的插入不拦截。 */
  function handleBeforeInput(event: FormEvent<HTMLDivElement>) {
    const native = event.nativeEvent as InputEvent
    if (native.isComposing) {
      return
    }
    const inputType = native.inputType
    if (inputType === 'insertParagraph' || inputType === 'insertLineBreak') {
      event.preventDefault()
      const el = editorRef.current
      if (el) {
        insertTextAtCaret(el, '\n')
        syncFromDom()
      }
    }
  }

  function handlePaste(event: ClipboardEvent<HTMLDivElement>) {
    const files = clipboardFiles(event.clipboardData)
    if (files.length > 0) {
      event.preventDefault()
      addFilesToDraft(files)
    }
    // 无文件时保留浏览器默认粘贴；随后由 onInput 统一回流 ordered parts。
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    const files = Array.from(event.dataTransfer?.files ?? [])
    if (files.length > 0) {
      event.preventDefault()
      addFilesToDraft(files)
      return
    }
    const text = event.dataTransfer?.getData('text/plain') ?? ''
    if (text) {
      event.preventDefault()
      const el = editorRef.current
      if (el) {
        insertTextAtCaret(el, text)
        syncFromDom()
      }
    }
  }

  function handleFileInputChange(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? [])
    if (files.length > 0) {
      addFilesToDraft(files)
    }
    event.target.value = ''
    focusComposer()
  }

  function handleRemoveUpload(upload: AttachmentUpload) {
    if (disabled) {
      return
    }
    const next = removePartsForUpload(upload, parts)
    if (next.length !== parts.length) {
      changeDraft(next)
    }
    releaseUpload(upload.localId)
  }

  return (
    <div className="thread-composer" hidden={!active} aria-hidden={!active}>
      <ThreadCommandPalette
        open={paletteOpen}
        query={query}
        commands={commands}
        activeIndex={activeIndex}
        onActiveIndexChange={setActiveIndex}
        onSelect={handleSelect}
      />
      <AttachmentStrip
        uploads={uploads}
        parts={parts}
        disabled={disabled}
        onRemove={handleRemoveUpload}
        onRetry={(upload) => retryUpload(upload.localId)}
      />
      <div className="thread-dock">
        <button
          type="button"
          className="thread-dock-add"
          aria-label={t('ai.runtime.composer.openCommands')}
          aria-expanded={paletteOpen}
          disabled={disabled}
          onMouseDown={(event) => {
            // 鼠标打开菜单时保留 editor 的 focus、caret 与 selection。
            event.preventDefault()
          }}
          onClick={() => {
            if (paletteOpen) {
              closeCommandPalette()
              return
            }
            setPlusMenuOpen(true)
            focusComposer()
          }}
        >
          <Plus aria-hidden="true" />
        </button>
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
          onInput={syncFromDom}
          onKeyDown={handleKeyDown}
          onBeforeInput={handleBeforeInput}
          onPaste={handlePaste}
          onDrop={handleDrop}
        />
        <button
          className="thread-dock-send"
          type="button"
          aria-label={t('ai.runtime.composer.send')}
          onClick={() => {
            handleSubmit()
            focusComposer()
          }}
          disabled={!canSend}
        >
          <ArrowUp className="send-icon" aria-hidden="true" />
        </button>
      </div>
      <input
        ref={fileInputRef}
        type="file"
        multiple
        hidden
        className="composer-file-input-hidden"
        tabIndex={-1}
        aria-hidden="true"
        onChange={handleFileInputChange}
      />
    </div>
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

function canNavigateMessageHistoryFromCaret(
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
