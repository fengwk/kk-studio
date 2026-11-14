import {
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  type ChangeEvent,
} from 'react'
import { X } from 'lucide-react'
import { ThreadCommandPalette } from '@/features/thread/runtime/thread-panel/ThreadCommandPalette'
import type { ComposerEditorHandle } from '@/features/thread/runtime/thread-panel/ComposerEditor'
import type { ThreadComposerControlMenu } from '@/features/thread/runtime/thread-panel/ThreadComposerControls'
import { ThreadComposerDock } from '@/features/thread/runtime/thread-panel/ThreadComposerDock'
import { THREAD_COMMANDS } from '@/features/thread/runtime/thread-panel/thread-commands'
import { slashQueryOf } from '@/features/thread/composer/composer-parts'
import {
  canSubmitParts,
  partForUpload,
  removePartsForUpload,
  useAttachmentUploads,
  type AttachmentUpload,
  type AttachmentUploadError,
} from '@/features/thread/composer'
import {
  localDraftSnapshot,
  resolveSubmissionPayload,
} from '@/features/thread/runtime/thread-panel/composer-submission'
import type {
  ThreadComposerHandle,
  ThreadComposerProps,
} from '@/features/thread/runtime/thread-panel/thread-composer-contract'
import { useAttachmentImageTiers } from '@/features/thread/runtime/thread-panel/useAttachmentImageTiers'
import { useComposerFocus } from '@/features/thread/runtime/thread-panel/useComposerFocus'
import { useComposerMessageHistory } from '@/features/thread/runtime/thread-panel/useComposerMessageHistory'
import { useComposerOverlays } from '@/features/thread/runtime/thread-panel/useComposerOverlays'
import { useComposerPalette } from '@/features/thread/runtime/thread-panel/useComposerPalette'
import { useComposerSubmissionSettle } from '@/features/thread/runtime/thread-panel/useComposerSubmissionSettle'
import { useComposerToast } from '@/features/thread/runtime/thread-panel/useComposerToast'
import { useI18n } from '@/shared/i18n'
import { IconButton } from '@/shared/ui/controls/IconButton'
import './thread-composer.css'

const EMPTY_USER_MESSAGES: readonly string[] = []

export type { ThreadComposerHandle, ThreadComposerProps }

/**
 * Composer 底层的 ordered Pill 输入区。
 *
 * 原生 contenteditable editor + `contenteditable=false` attachment/resource pill；draft 是
 * ordered {@link ComposerPart}（TEXT/ATTACHMENT/RESOURCE），DOM pill 携带
 * `data-part-id`、`data-part-type` 与对应引用字段。只有文件粘贴/拖放/选择会创建
 * attachment pill，typed '@filename' 始终是文本。
 *
 * 上传注册表（strip）由组件内部持有；storageService/hashFile 可注入，使组件不依赖
 * 任何 Thread 身份或 AI 状态。本组件只做编排：焦点、覆盖层、命令表、上传与提交
 * settle 各自在专属 hook 里，dock 与提示条的渲染在同目录的展示组件里。
 */
export function ThreadComposer({
  ref,
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
  onCommandPaletteVisibilityChange,
  active = true,
  restoreOnActivate = true,
  settings,
  scope,
}: ThreadComposerProps) {
  const { t } = useI18n()
  const containerRef = useRef<HTMLDivElement>(null)
  const editorRef = useRef<HTMLDivElement>(null)
  const editorApiRef = useRef<ComposerEditorHandle>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const { toast, showToast, dismissToast } = useComposerToast()

  const { changeDraft, navigate: navigateMessageHistory } = useComposerMessageHistory({
    parts,
    historicalUserMessages,
    queuedUserMessages,
    onPartsChange,
    onHistoryPartsChange,
  })

  const handleUploadError = useCallback((err: AttachmentUploadError) => {
    const reasonText = err.reason?.trim()
    const boundedReason = reasonText
      ? (reasonText.length > 120 ? `${reasonText.slice(0, 117)}…` : reasonText)
      : t('ai.runtime.composer.uploadFailed')
    showToast(t('ai.runtime.composer.uploadFailedDetail', {
      name: err.filename,
      reason: boundedReason,
    }))

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

  const { handleTierChange } = useAttachmentImageTiers({
    parts,
    uploads,
    updateImageTier,
    changeDraft,
    editorApiRef,
  })

  const slashQuery = slashQueryOf(parts)

  const {
    plusMenuOpen,
    setPlusMenuOpen,
    controlMenu,
    setControlMenu,
    closeControlMenu,
    closeOverlay,
    dismissOverlays,
    handleLeaveRegion,
  } = useComposerOverlays({ active, slashMode: slashQuery != null })

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
    restoreOnActivate,
    focusOnEscape,
    pending,
    closeOverlay,
    onLeaveRegion: handleLeaveRegion,
  })

  /** 切换底栏设置菜单；可选把焦点还给编辑器（菜单关闭后继续键入）。 */
  const changeControlMenu = useCallback((
    next: ThreadComposerControlMenu,
    restoreComposerFocus = false,
  ) => {
    setPlusMenuOpen(false)
    setControlMenu(next)
    if (restoreComposerFocus) {
      focusComposer(true)
    }
  }, [focusComposer, setControlMenu, setPlusMenuOpen])

  // 进行中的 HTTP 变更不能阻塞连续提交；附件未全部 ready 时也不能发送。
  const canSend = useMemo(
    () => !disabled && slashQuery == null && canSubmitParts(parts, uploads),
    [disabled, parts, slashQuery, uploads],
  )

  // 提交 settle 状态机：本地草稿快照、发送失败恢复与成功后 detached 上传释放。
  const { commit } = useComposerSubmissionSettle({
    parts,
    pending,
    uploads,
    markDetached,
    releaseUpload,
  })

  const submit = useCallback(() => {
    if (!canSend) {
      return
    }
    setPlusMenuOpen(false)
    // 草稿始终引用客户端 localId；提交 payload 在序列化前解析为服务端 upload
    // 句柄（避免「上传完成异步改写 parts」与用户编辑竞态）。恢复快照必须保存
    // 本地草稿（trim 后），与 payload 分开——恢复比对只命中本地 id 草稿。
    const resolved = resolveSubmissionPayload(parts, uploads)
    const localDraft = localDraftSnapshot(parts, uploads)
    commit(localDraft)
    onSubmit(resolved, localDraft)
  }, [canSend, commit, onSubmit, parts, setPlusMenuOpen, uploads])

  const {
    paletteOpen,
    query,
    activeIndex,
    setActiveIndex,
    togglePalette,
    selectCommand,
    handleEditorKeyDown,
  } = useComposerPalette({
    active,
    disabled,
    isFocused,
    plusMenuOpen,
    setPlusMenuOpen,
    closeControlMenu,
    slashQuery,
    commands,
    parts,
    settings,
    fileInputRef,
    changeDraft,
    onCommand,
    onChangeControlMenu: (menu) => changeControlMenu(menu),
    closeOverlay,
    focusComposer,
    blurComposer,
    onSubmit: submit,
  })

  useComposerPaletteVisibilityNotice(paletteOpen, onCommandPaletteVisibilityChange)

  useImperativeHandle(ref, () => ({
    focus: () => focusComposer(true),
  }))

  function addFilesToDraft(files: File[]) {
    if (disabled || files.length === 0) {
      return
    }
    const pillParts = addFiles(files).map(partForUpload)
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

  function handleSend() {
    submit()
    focusComposer()
  }

  function handleEditorMouseDown() {
    setControlMenu(null)
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
        <div className="composer-toast" role="alert" aria-live="assertive">
          <span className="composer-toast-message">{toast}</span>
          <IconButton
            className="composer-toast-dismiss"
            label={t('ai.runtime.composer.dismissNotification')}
            onClick={dismissToast}
          >
            <X aria-hidden="true" />
          </IconButton>
        </div>
      ) : null}
      <ThreadCommandPalette
        open={paletteOpen}
        query={query}
        commands={commands}
        activeIndex={activeIndex}
        onActiveIndexChange={setActiveIndex}
        onSelect={selectCommand}
      />
      <ThreadComposerDock
        editorRef={editorRef}
        editorApiRef={editorApiRef}
        fileInputRef={fileInputRef}
        parts={parts}
        uploads={uploads}
        disabled={disabled}
        canSend={canSend}
        paletteOpen={paletteOpen}
        settings={settings}
        controlMenu={controlMenu}
        onPartsChange={changeDraft}
        onEditStart={dismissOverlays}
        onEditorMouseDown={handleEditorMouseDown}
        onEditorKeyDown={handleEditorKeyDown}
        onNavigateHistory={navigateMessageHistory}
        onFiles={addFilesToDraft}
        onRemoveUpload={handleRemoveUpload}
        onRetryUpload={retryComplete}
        onTierChange={handleTierChange}
        onTogglePalette={togglePalette}
        onSend={handleSend}
        onMenuChange={changeControlMenu}
        onFileInputChange={handleFileInputChange}
      />
    </div>
  )
}

/**
 * 命令表可见性的轻量通知：打开与关闭各通知一次，卸载时若仍打开则补发一次关闭。
 * 调用方据此暂停/恢复外层快捷键，不发起任何网络请求。
 */
function useComposerPaletteVisibilityNotice(
  paletteOpen: boolean,
  onVisibilityChange?: (visible: boolean) => void,
) {
  useEffect(() => {
    onVisibilityChange?.(paletteOpen)
    return () => {
      if (paletteOpen) {
        onVisibilityChange?.(false)
      }
    }
  }, [paletteOpen, onVisibilityChange])
}
