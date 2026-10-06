import {
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type Ref,
} from 'react'
import { ArrowUp, Plus, X } from 'lucide-react'
import {
  ThreadCommandPalette,
} from '@/features/ai/runtime/thread-panel/ThreadCommandPalette'
import {
  ComposerEditor,
  type ComposerEditorHandle,
} from '@/features/ai/runtime/thread-panel/ComposerEditor'
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
  hasMessageContent,
  partsKey,
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

export type ComposerPreviewDisabledReason =
  | 'EMPTY_DRAFT'
  | 'SLASH_COMMAND'
  | 'GOAL_COMMAND'
  | 'UPLOADS_PENDING'
  | 'COMPOSER_DISABLED'

export interface ComposerPreviewReadiness {
  canPreview: boolean
  reason: ComposerPreviewDisabledReason | null
}

export interface ThreadComposerHandle {
  preparePreview: () => { payload: ComposerPart[]; localDraft: ComposerPart[] } | null
}

export interface ThreadComposerProps {
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
  scope?: string
  ref?: Ref<ThreadComposerHandle>
  onPreviewReadinessChange?: (readiness: ComposerPreviewReadiness) => void
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
  ref,
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
  scope,
  onPreviewReadinessChange,
}: ThreadComposerProps) {
  const { t } = useI18n()
  const containerRef = useRef<HTMLDivElement>(null)
  const editorRef = useRef<HTMLDivElement>(null)
  const editorApiRef = useRef<ComposerEditorHandle>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

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

  /** 编辑器编辑交互：关闭命令表与底栏设置菜单。 */
  const dismissOverlays = useCallback(() => {
    setPlusMenuOpen(false)
    setControlMenu(null)
  }, [])

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

    // 失败 pill 的 DOM 回滚由编辑器执行；组合期间由编辑器延后到 compositionend。
    editorApiRef.current?.removeAttachmentPills([err.localId])
  }, [showToast, t])

  const {
    uploads,
    addFiles,
    releaseUpload,
    retryComplete,
    markDetached,
    updateImageTier,
  } = useAttachmentUploads({ storageService, hashFile, onError: handleUploadError, scope, parts })

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
      // 先就地更新已渲染 pill 的档位属性，再做受控回流；否则整树重建会丢光标。
      editorApiRef.current?.updateAttachmentImageTier(
        upload.uploadId ? [upload.localId, upload.uploadId] : [upload.localId],
        tier,
      )
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

  const previewReadiness = useMemo((): ComposerPreviewReadiness => {
    if (disabled) {
      return { canPreview: false, reason: 'COMPOSER_DISABLED' }
    }
    if (slashMode) {
      return { canPreview: false, reason: 'SLASH_COMMAND' }
    }
    if (isGoalCommand) {
      return { canPreview: false, reason: 'GOAL_COMMAND' }
    }
    const hasAttachments = parts.some((part) => part.type === 'attachment')
    if (hasAttachments && !canSubmitParts(parts, uploads)) {
      return { canPreview: false, reason: 'UPLOADS_PENDING' }
    }
    const trimmed = trimMessageParts(parts)
    if (!hasMessageContent(trimmed)) {
      return { canPreview: false, reason: 'EMPTY_DRAFT' }
    }
    return { canPreview: true, reason: null }
  }, [disabled, slashMode, isGoalCommand, parts, uploads])

  useEffect(() => {
    onPreviewReadinessChange?.(previewReadiness)
  }, [onPreviewReadinessChange, previewReadiness])

  useImperativeHandle(ref, () => ({
    preparePreview: () => {
      const domParts = editorApiRef.current?.syncDraft() ?? null
      if (!previewReadiness.canPreview) {
        return null
      }
      const localDraft = localDraftSnapshot()
      // DOM 同步会异步更新受控 parts；尚未对齐时不拼接旧 payload 与新草稿。
      if (domParts == null || partsKey(trimMessageParts(domParts)) !== partsKey(trimMessageParts(parts))) {
        return null
      }
      return {
        payload: resolveUploadIds(parts),
        localDraft,
      }
    },
  }))

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

  function addFilesToDraft(files: File[]) {
    if (disabled || files.length === 0) {
      return
    }
    const added = addFiles(files)
    const pillParts = added.map(partForUpload)
    if (pillParts.length === 0) {
      return
    }
    const editor = editorApiRef.current
    if (!editor) {
      changeDraft([...parts, ...pillParts])
      return
    }
    editor.insertParts(pillParts)
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

  function localDraftSnapshot() {
    return trimMessageParts(
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
    const localDraft = localDraftSnapshot()
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

  /** 编辑器未消费的按键：命令表导航优先，其次 Enter 提交。返回是否已消费。 */
  function handleEditorKeyDown(event: KeyboardEvent<HTMLDivElement>): boolean {
    if (paletteOpen && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault()
      event.stopPropagation()
      const delta = event.key === 'ArrowDown' ? 1 : -1
      setActiveIndex((current) => stepEnabledCommandIndex(filteredCommands, current, delta))
      return true
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
        return true
      }
      event.preventDefault()
      handleSubmit()
      focusComposer()
      return true
    }
    return false
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
          onRetry={retryComplete}
          onTierChange={handleTierChange}
        />
        <ComposerEditor
          ref={editorApiRef}
          editorRef={editorRef}
          parts={parts}
          disabled={disabled}
          onPartsChange={changeDraft}
          onEditStart={dismissOverlays}
          onEditorMouseDown={() => setControlMenu(null)}
          onKeyDown={handleEditorKeyDown}
          onNavigateHistory={navigateMessageHistory}
          onFiles={addFilesToDraft}
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
