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
  ThreadComposerControls,
  type ThreadComposerControlMenu,
  type ThreadComposerSettingsInput,
} from '@/features/ai/runtime/thread-panel/ThreadComposerControls'
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
  useAttachmentUploads,
  type AttachmentUpload,
  type HashFile,
  type StorageService,
} from '@/features/ai/composer'
import { useComposerMessageHistory } from '@/features/ai/runtime/thread-panel/useComposerMessageHistory'
import { useComposerFocus } from '@/features/ai/runtime/thread-panel/useComposerFocus'
import { useComposerSubmissionSettle } from '@/features/ai/runtime/thread-panel/useComposerSubmissionSettle'
import { useI18n } from '@/shared/i18n'

const EMPTY_USER_MESSAGES: readonly string[] = []

/**
 * 共享的 ordered Pill Composer（OpenCode 风格）。
 *
 * 原生 contenteditable editor + `contenteditable=false` attachment/resource pill；draft 是
 * ordered {@link ComposerPart}（TEXT/ATTACHMENT/RESOURCE），DOM pill 携带
 * `data-part-id`、`data-part-type` 与对应引用字段。只有文件粘贴/拖放/选择会创建
 * attachment pill，typed '@filename' 始终是文本。
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
  settings,
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
  /** 双层 Composer 底栏的受控 Permission 与 Model/Variant 设置。 */
  settings?: ThreadComposerSettingsInput
}) {
  const { t } = useI18n()
  const editorRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
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
  const [controlMenu, setControlMenu] = useState<ThreadComposerControlMenu>(null)
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

  // 全局 Escape 的覆盖层关闭：control menu 优先，其次 palette（slash 模式同时
  // 清空命令草稿）；焦点状态机在命中后关闭覆盖层再统一恢复焦点。
  const closeOverlay = useCallback(() => {
    if (controlMenu != null) {
      setPlusMenuOpen(false)
      setControlMenu(null)
      return
    }
    if (paletteMode != null) {
      setPlusMenuOpen(false)
      if (paletteMode === 'slash') {
        changeDraft([])
      }
    }
  }, [changeDraft, controlMenu, paletteMode])

  // 组合焦点状态机：定时重试/覆盖层关闭后恢复/active 恢复/Escape/pending
  // 完成后自动聚焦/卸载清理。
  const { focusComposer } = useComposerFocus({
    editorRef,
    disabled,
    active,
    focusOnEscape,
    pending,
    closeOverlay,
  })

  // 提交 settle 状态机：本地草稿快照、发送失败恢复与成功后 detached 上传释放。
  const { commit } = useComposerSubmissionSettle({
    parts,
    pending,
    uploads,
    markDetached,
    releaseUpload,
  })

  const closeCommandPalette = useCallback((forceCaretAtEnd = false) => {
    setPlusMenuOpen(false)
    if (paletteMode === 'slash') {
      changeDraft([])
    }
    focusComposer(forceCaretAtEnd)
  }, [changeDraft, focusComposer, paletteMode])

  const changeControlMenu = useCallback((
    next: ThreadComposerControlMenu,
    restoreComposerFocus = false,
  ) => {
    setPlusMenuOpen(false)
    setControlMenu(next)
    if (restoreComposerFocus) {
      focusComposer(true)
    }
  }, [focusComposer])

  // interaction panel 接管时关闭底栏菜单；焦点恢复请求（active 重新打开）由
  // 焦点状态机 hook 负责。
  useEffect(() => {
    if (!active) {
      setControlMenu(null)
    }
  }, [active])

  const slashModeRef = useRef(slashMode)
  useEffect(() => {
    const startedSlash = !slashModeRef.current && slashMode
    slashModeRef.current = slashMode
    if (startedSlash && controlMenu != null) {
      setControlMenu(null)
    }
  }, [controlMenu, slashMode])

  useEffect(() => {
    if (!paletteOpen) {
      return
    }
    setActiveIndex(firstEnabledCommandIndex(filteredCommands))
  }, [paletteOpen, query, filteredCommands])

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

  /** 从当前 DOM 提取 parts 并回传（输入/粘贴/删除后统一入口）。 */
  function syncFromDom() {
    const el = editorRef.current
    if (!el) {
      return
    }
    if (plusMenuOpen) {
      setPlusMenuOpen(false)
    }
    if (controlMenu != null) {
      setControlMenu(null)
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
    // 草稿始终引用客户端 localId；提交 payload 在序列化前解析为服务端 upload
    // 句柄（避免「上传完成异步改写 parts」与用户编辑竞态）。恢复快照必须保存
    // 本地草稿（trim 后），与 payload 分开——恢复比对只命中本地 id 草稿。
    const resolved = resolveUploadIds(parts)
    const localDraft = trimMessageParts(parts)
    commit(localDraft)
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
    if (command.id === 'models') {
      if (settings) {
        changeControlMenu('model')
      }
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
      <div className="thread-dock">
        <AttachmentStrip
          uploads={uploads}
          parts={parts}
          disabled={disabled}
          onRemove={handleRemoveUpload}
          onRetry={(upload) => retryUpload(upload.localId)}
        />
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
          onMouseDown={() => {
            if (controlMenu != null) {
              setControlMenu(null)
            }
          }}
        />
        <div className="thread-dock-controls">
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
              setControlMenu(null)
              setPlusMenuOpen(true)
              focusComposer()
            }}
          >
            <Plus aria-hidden="true" />
          </button>
          {settings ? (
            <ThreadComposerControls
              settings={settings}
              menu={controlMenu}
              disabled={disabled}
              onMenuChange={changeControlMenu}
            />
          ) : <span className="thread-dock-controls-spacer" />}
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
