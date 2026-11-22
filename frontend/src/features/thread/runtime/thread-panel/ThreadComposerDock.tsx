import { ArrowUp, Plus } from 'lucide-react'
import type { ChangeEvent, KeyboardEvent, RefObject } from 'react'
import { AttachmentStrip } from '@/features/thread/composer/attachment-strip'
import {
  ComposerEditor,
  type ComposerEditorHandle,
} from '@/features/thread/runtime/thread-panel/ComposerEditor'
import {
  ThreadComposerControls,
  type ThreadComposerControlMenu,
  type ThreadComposerSettingsInput,
} from '@/features/thread/runtime/thread-panel/ThreadComposerControls'
import type { ComposerPart, ImageInputTier } from '@/features/thread/composer/composer-parts'
import type { AttachmentUpload } from '@/features/thread/composer'
import { useI18n } from '@/shared/i18n'

/**
 * Composer 的 dock：附件条、编辑器、底栏控件与隐藏的文件输入。
 *
 * 纯展示：所有状态与上传注册表都在编排层，这里只把既成事实映射成控件，
 * 并把每个交互原样上报。
 */
export function ThreadComposerDock({
  editorRef,
  editorApiRef,
  fileInputRef,
  parts,
  uploads,
  disabled,
  canSend,
  paletteOpen,
  editorAriaControls,
  editorAriaActiveDescendant,
  settings,
  controlMenu,
  onPartsChange,
  onEditStart,
  onEditorMouseDown,
  onEditorKeyDown,
  onNavigateHistory,
  onFiles,
  onRemoveUpload,
  onRetryUpload,
  onTierChange,
  onTogglePalette,
  onSend,
  onMenuChange,
  onFileInputChange,
}: {
  editorRef: RefObject<HTMLDivElement | null>
  editorApiRef: RefObject<ComposerEditorHandle | null>
  fileInputRef: RefObject<HTMLInputElement | null>
  parts: ComposerPart[]
  uploads: AttachmentUpload[]
  disabled: boolean
  canSend: boolean
  paletteOpen: boolean
  /** 命令表打开时编辑器声明的 listbox id；关闭时为 undefined。 */
  editorAriaControls?: string
  /** 命令表当前游标项的 option id，供编辑器用 aria-activedescendant 播报。 */
  editorAriaActiveDescendant?: string
  settings?: ThreadComposerSettingsInput
  controlMenu: ThreadComposerControlMenu
  onPartsChange: (parts: ComposerPart[]) => void
  onEditStart: () => void
  onEditorMouseDown: () => void
  onEditorKeyDown: (event: KeyboardEvent<HTMLDivElement>) => boolean
  onNavigateHistory: (direction: 'previous' | 'next') => boolean
  onFiles: (files: File[]) => void
  onRemoveUpload: (upload: AttachmentUpload) => void
  onRetryUpload: (upload: AttachmentUpload) => void
  onTierChange: (upload: AttachmentUpload, tier: ImageInputTier) => void
  onTogglePalette: () => void
  onSend: () => void
  onMenuChange: (menu: ThreadComposerControlMenu, restoreComposerFocus?: boolean) => void
  onFileInputChange: (event: ChangeEvent<HTMLInputElement>) => void
}) {
  return (
    <div className="thread-dock">
      <AttachmentStrip
        uploads={uploads}
        parts={parts}
        disabled={disabled}
        onRemove={onRemoveUpload}
        onRetry={onRetryUpload}
        onTierChange={onTierChange}
      />
      <ComposerEditor
        ref={editorApiRef}
        editorRef={editorRef}
        parts={parts}
        disabled={disabled}
        ariaControls={editorAriaControls}
        ariaActiveDescendant={editorAriaActiveDescendant}
        onPartsChange={onPartsChange}
        onEditStart={onEditStart}
        onEditorMouseDown={onEditorMouseDown}
        onKeyDown={onEditorKeyDown}
        onNavigateHistory={onNavigateHistory}
        onFiles={onFiles}
      />
      <ThreadComposerDockControls
        disabled={disabled}
        canSend={canSend}
        paletteOpen={paletteOpen}
        settings={settings}
        controlMenu={controlMenu}
        onTogglePalette={onTogglePalette}
        onSend={onSend}
        onMenuChange={onMenuChange}
      />
      <input
        ref={fileInputRef}
        type="file"
        multiple
        hidden
        className="composer-file-input-hidden"
        tabIndex={-1}
        aria-hidden="true"
        onChange={onFileInputChange}
      />
    </div>
  )
}

/** 底栏：plus（命令面板）、Permission/Model/Variant 设置与发送。 */
function ThreadComposerDockControls({
  disabled,
  canSend,
  paletteOpen,
  settings,
  controlMenu,
  onTogglePalette,
  onSend,
  onMenuChange,
}: {
  disabled: boolean
  canSend: boolean
  paletteOpen: boolean
  settings?: ThreadComposerSettingsInput
  controlMenu: ThreadComposerControlMenu
  onTogglePalette: () => void
  onSend: () => void
  onMenuChange: (menu: ThreadComposerControlMenu, restoreComposerFocus?: boolean) => void
}) {
  const { t } = useI18n()
  return (
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
        onClick={onTogglePalette}
      >
        <Plus aria-hidden="true" />
      </button>
      {settings ? (
        <ThreadComposerControls
          settings={settings}
          menu={controlMenu}
          disabled={disabled}
          onMenuChange={onMenuChange}
        />
      ) : <span className="thread-dock-controls-spacer" />}
      <button
        className="thread-dock-send"
        type="button"
        aria-label={t('ai.runtime.composer.send')}
        onClick={onSend}
        disabled={!canSend}
      >
        <ArrowUp className="send-icon" aria-hidden="true" />
      </button>
    </div>
  )
}
