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
import { ArrowUp, Plus, X } from 'lucide-react'
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
  type ImageInputTier,
} from '@/features/ai/composer/composer-parts'
import {
  canSubmitParts,
  partForUpload,
  removePartsForUpload,
  useAttachmentUploads,
  type AttachmentUpload,
  type AttachmentUploadError,
  type HashFile,
  type StorageService,
} from '@/features/ai/composer'
import { useComposerMessageHistory } from '@/features/ai/runtime/thread-panel/useComposerMessageHistory'
import { useComposerFocus } from '@/features/ai/runtime/thread-panel/useComposerFocus'
import { useComposerSubmissionSettle } from '@/features/ai/runtime/thread-panel/useComposerSubmissionSettle'
import { useI18n } from '@/shared/i18n'

const EMPTY_USER_MESSAGES: readonly string[] = []

function extractGoalCommand(parts: ComposerPart[]): { isGoalCommand: boolean; objective: string | null } {
  let text = ''
  for (const part of parts) {
    if (part.type === 'text') {
      text += part.text
    }
  }
  const match = text.match(/^\/goal(?:\s+(.*))?$/s)
  if (!match) {
    return { isGoalCommand: false, objective: null }
  }
  const rawArg = match[1] ?? ''
  const trimmed = rawArg.trim()
  return {
    isGoalCommand: true,
    objective: trimmed.length > 0 ? trimmed : null,
  }
}

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
  onSubmitGoal,
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
   * - localDraft：客户端 localId 的草稿快照（trim 后），用于失败恢复与
   *   PendingAcceptance——恢复比对只命中本地 id 草稿。
   */
  onSubmit: (payload: ComposerPart[], localDraft: ComposerPart[]) => void
  onSubmitGoal?: (goalText: string, localDraft: ComposerPart[]) => void
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
  const containerRef = useRef<HTMLDivElement>(null)
  const editorRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const isComposingRef = useRef(false)
  const pendingFailedUploadIdsRef = useRef<Set<string>>(new Set())

  const [toast, setToast] = useState<string | null>(null)
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const showToast = useCallback((message: string) => {
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current)
      toastTimerRef.current = null
    }
    setToast(message)
    toastTimerRef.current = setTimeout(() => {
      setToast(null)
      toastTimerRef.current = null
    }, 5000)
  }, [])

  const dismissToast = useCallback(() => {
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current)
      toastTimerRef.current = null
    }
    setToast(null)
  }, [])

  useEffect(() => () => {
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current)
    }
  }, [])

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

  const [plusMenuOpen, setPlusMenuOpen] = useState(false)
  const [controlMenu, setControlMenu] = useState<ThreadComposerControlMenu>(null)

  /** 从当前 DOM 提取 parts 并回传（输入/粘贴/删除后统一入口）。 */
  const syncFromDom = useCallback((force = false) => {
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
    if (force || partsKey(next) !== partsKey(parts)) {
      changeDraft(next)
    }
  }, [changeDraft, controlMenu, parts, plusMenuOpen])

  const handleUploadError = useCallback((err: AttachmentUploadError) => {
    const reasonText = err.reason?.trim()
    const boundedReason = reasonText
      ? (reasonText.length > 120 ? `${reasonText.slice(0, 117)}…` : reasonText)
      : t('ai.runtime.composer.uploadFailed')
    const message = t('ai.runtime.composer.uploadFailedDetail', {
      name: err.filename,
      reason: boundedReason,
    })
    showToast(message)

    if (isComposingRef.current) {
      pendingFailedUploadIdsRef.current.add(err.localId)
      return
    }

    const el = editorRef.current
    if (el) {
      const pills = el.querySelectorAll<HTMLElement>(
        `span[data-part-type="attachment"][data-upload-id="${err.localId}"]`,
      )
      if (pills.length > 0) {
        pills.forEach((pill) => pill.remove())
        // 上传可能在最新 parts effect 生效前失败；以当前 DOM 强制覆盖受控草稿，
        // 避免旧闭包误判相等后把失败 pill 重新插回。
        syncFromDom(true)
      }
    }
  }, [showToast, syncFromDom, t])

  const {
    uploads,
    addFiles,
    releaseUpload,
    markDetached,
    updateImageTier,
  } = useAttachmentUploads({ storageService, hashFile, onError: handleUploadError })

  const handleTierChange = useCallback(
    (upload: AttachmentUpload, tier: ImageInputTier) => {
      updateImageTier(upload.localId, tier)
      const next = parts.map((part) => {
        if (
          part.type === 'attachment'
          && (part.uploadId === upload.localId || (upload.uploadId && part.uploadId === upload.uploadId))
        ) {
          return { ...part, imageTier: tier }
        }
        return part
      })
      const el = editorRef.current
      if (el) {
        const pills = el.querySelectorAll<HTMLElement>(
          `span[data-part-type="attachment"][data-upload-id="${upload.localId}"]`,
        )
        pills.forEach((p) => {
          p.dataset.imageTier = tier
        })
        if (upload.uploadId) {
          const serverPills = el.querySelectorAll<HTMLElement>(
            `span[data-part-type="attachment"][data-upload-id="${upload.uploadId}"]`,
          )
          serverPills.forEach((p) => {
            p.dataset.imageTier = tier
          })
        }
      }
      changeDraft(next)
    },
    [changeDraft, parts, updateImageTier],
  )

  const goalCommandInfo = extractGoalCommand(parts)
  const isGoalCommand = goalCommandInfo.isGoalCommand
  const isGoalWithObjective = isGoalCommand && goalCommandInfo.objective != null
  const slashQuery = isGoalWithObjective ? null : slashQueryOf(parts)
  const slashMode = slashQuery != null

  const [activeIndex, setActiveIndex] = useState(0)
  const draftIsEmpty = parts.every(
    (part) => part.type === 'text' && part.text.trim() === '',
  )

  // 关闭覆盖层只改变显隐；若 control menu 优先处理则返回 true 避免外部继续 blur。
  const closeOverlay = useCallback((): boolean => {
    if (controlMenu != null) {
      setControlMenu(null)
      return true
    }
    setPlusMenuOpen(false)
    return false
  }, [controlMenu])

  const handleLeaveRegion = useCallback(() => {
    setPlusMenuOpen(false)
    setControlMenu(null)
  }, [])

  // 组合焦点状态机：定时重试/覆盖层关闭后恢复/active 恢复/Escape/pending
  // 完成后自动聚焦/卸载清理。
  const {
    isFocused,
    focusComposer,
    blurComposer,
    handleKeyDown: handleRegionKeyDown,
  } = useComposerFocus({
    editorRef,
    containerRef,
    disabled,
    active,
    focusOnEscape,
    pending,
    closeOverlay,
    onLeaveRegion: handleLeaveRegion,
  })

  const paletteMode =
    active && !disabled && isFocused
      ? plusMenuOpen
        ? 'menu'
        : slashMode
          ? 'slash'
          : null
      : null
  const paletteOpen = paletteMode != null
  const query = paletteMode === 'slash' ? slashQuery ?? '' : ''
  const filteredCommands = useFilteredThreadCommands(query, commands)

  // 进行中的 HTTP 变更不能阻塞连续提交；附件未全部 ready 时也不能发送。
  const canSend = useMemo(
    () => !disabled && !slashMode && (isGoalCommand || canSubmitParts(parts, uploads)),
    [disabled, isGoalCommand, parts, slashMode, uploads],
  )

  // 提交 settle 状态机：本地草稿快照、发送失败恢复与成功后 detached 上传释放。
  const { commit } = useComposerSubmissionSettle({
    parts,
    pending,
    uploads,
    markDetached,
    releaseUpload,
  })

  const closeCommandPalette = useCallback((forceCaretAtEnd = false) => {
    closeOverlay()
    focusComposer(forceCaretAtEnd)
  }, [closeOverlay, focusComposer])

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
  }, [parts])

  function handleCompositionStart() {
    isComposingRef.current = true
  }

  function handleCompositionEnd() {
    isComposingRef.current = false
    const el = editorRef.current
    const hadFailedUploads = pendingFailedUploadIdsRef.current.size > 0
    if (hadFailedUploads && el) {
      for (const localId of pendingFailedUploadIdsRef.current) {
        const pills = el.querySelectorAll<HTMLElement>(
          `span[data-part-type="attachment"][data-upload-id="${localId}"]`,
        )
        pills.forEach((pill) => pill.remove())
      }
    }
    pendingFailedUploadIdsRef.current.clear()
    syncFromDom(hadFailedUploads)
  }

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

  function addFilesToDraft(files: File[]) {
    if (disabled || files.length === 0) {
      return
    }
    const added = addFiles(files)
    const pillParts = added.map(partForUpload)
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
      const record = uploads.find(
        (upload) => upload.localId === part.uploadId || upload.uploadId === part.uploadId,
      )
      const imageTier = part.imageTier ?? record?.imageTier
      if (record?.uploadId) {
        return {
          ...part,
          uploadId: record.uploadId,
          ...(imageTier ? { imageTier } : {}),
        }
      }
      return {
        ...part,
        ...(imageTier ? { imageTier } : {}),
      }
    })
  }

  function handleSubmit() {
    if (!canSend) {
      return
    }
    const goalInfo = extractGoalCommand(parts)
    if (goalInfo.isGoalCommand && goalInfo.objective != null) {
      const goalCommand = commands.find((cmd) => cmd.id === 'goal')
      if (!goalCommand || goalCommand.disabled) {
        showToast(goalCommand?.disabledReason || t('ai.runtime.goal.notAllowed'))
        return
      }
      if (Array.from(goalInfo.objective).length > 2000) {
        showToast(t('ai.runtime.goal.errorTooLong'))
        return
      }
      setPlusMenuOpen(false)
      const localDraft = trimMessageParts(parts)
      commit(localDraft)
      const remainingParts = parts.filter((part) => part.type !== 'text')
      try {
        if (onSubmitGoal) {
          onSubmitGoal(goalInfo.objective, localDraft)
        }
        changeDraft(remainingParts)
      } catch {
        // preserve draft on sync error
      }
      return
    }
    if (goalInfo.isGoalCommand && goalInfo.objective == null) {
      const goalCommand = commands.find((cmd) => cmd.id === 'goal')
      if (!goalCommand || goalCommand.disabled) {
        showToast(goalCommand?.disabledReason || t('ai.runtime.goal.notAllowed'))
        return
      }
      setPlusMenuOpen(false)
      onCommand(goalCommand)
      return
    }
    setPlusMenuOpen(false)
    // 草稿始终引用客户端 localId；提交 payload 在序列化前解析为服务端 upload
    // 句柄（避免「上传完成异步改写 parts」与用户编辑竞态）。恢复快照必须保存
    // 本地草稿（trim 后），与 payload 分开——恢复比对只命中本地 id 草稿。
    const resolved = resolveUploadIds(parts)
    const localDraft = trimMessageParts(
      parts.map((part) => {
        if (part.type !== 'attachment') {
          return part
        }
        const record = uploads.find(
          (upload) => upload.localId === part.uploadId || upload.uploadId === part.uploadId,
        )
        const imageTier = part.imageTier ?? record?.imageTier
        return {
          ...part,
          ...(imageTier ? { imageTier } : {}),
        }
      }),
    )
    commit(localDraft)
    onSubmit(resolved, localDraft)
  }

  function handleSelect(command: ThreadCommand) {
    if (command.disabled) {
      return
    }
    const consumeSlashCommand = !plusMenuOpen && slashMode && command.id !== 'goal'
    setPlusMenuOpen(false)
    if (consumeSlashCommand) {
      changeDraft(parts.filter((part) => part.type !== 'text'))
    }
    if (command.id === 'goal') {
      focusComposer()
      onCommand(command)
      return
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
            changeDraft(next)
            return
          }
        }
        syncFromDom()
        return
      }
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
    if (native?.isComposing || isComposingRef.current) {
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
        insertTextAtCaret(el, text.replace(/\r\n|\r/g, '\n'))
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
    <div
      ref={containerRef}
      className="thread-composer"
      hidden={!active}
      aria-hidden={!active}
      onKeyDown={handleRegionKeyDown}
    >
      {toast ? (
        <div
          className="composer-toast"
          role="alert"
          aria-live="assertive"
        >
          <span className="composer-toast-message">{toast}</span>
          <button
            type="button"
            className="composer-toast-dismiss"
            aria-label={t('ai.runtime.composer.dismissNotification')}
            onClick={dismissToast}
          >
            <X aria-hidden="true" />
          </button>
        </div>
      ) : null}
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
          onTierChange={handleTierChange}
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
          onCompositionStart={handleCompositionStart}
          onCompositionEnd={handleCompositionEnd}
          onInput={handleInput}
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
                if (slashMode) {
                  blurComposer()
                } else {
                  closeCommandPalette()
                }
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
