import type { Ref } from 'react'
import type { StorageService } from '@/features/thread/composer'
import type { ComposerPart } from '@/features/thread/composer/composer-parts'
import type { ThreadComposerSettingsInput } from '@/features/thread/runtime/thread-panel/ThreadComposerControls'
import type { ThreadCommand } from '@/features/thread/runtime/thread-panel/thread-commands'
import type { HashFile } from '@/shared/lib/upload-file'

/** 命令式入口：调用方通过 ref 请求编辑器聚焦（快捷键、命令完成之后）。 */
export interface ThreadComposerHandle {
  focus: () => void
}

/** A recalled message retains its ordered resource parts as well as its text summary. */
export interface ComposerHistoryMessage {
  text: string
  parts: ComposerPart[]
}

/**
 * Composer 的输入契约。
 *
 * `parts` 是唯一草稿事实源，全部编辑经 `onPartsChange` 回流（受控组件）；
 * 上传注册表在组件内部，`storageService`/`hashFile` 让宿主导入自己的传输实现。
 */
export interface ThreadComposerProps {
  parts: ComposerPart[]
  pending: boolean
  disabled: boolean
  sendDisabled?: boolean
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
  onCommand: (command: ThreadCommand) => void
  commands?: ThreadCommand[]
  historicalUserMessages?: readonly (string | ComposerHistoryMessage)[]
  queuedUserMessages?: readonly (string | ComposerHistoryMessage)[]
  storageService?: StorageService
  hashFile?: HashFile
  /** 当前交互作用域是否允许全局 Escape 把焦点恢复到此 Composer。 */
  focusOnEscape?: boolean
  /** 命令面板可见性变化轻量通知（只在 open 变化时回调，不发起网络请求）。 */
  onCommandPaletteVisibilityChange?: (visible: boolean) => void
  /** false 时由同一 Composer 区域的 interaction panel 接管；组件保持挂载以保留上传状态。 */
  active?: boolean
  restoreOnActivate?: boolean
  /** 双层 Composer 底栏的受控 Permission 与 Model/Variant 设置。 */
  settings?: ThreadComposerSettingsInput
  scope?: string
  ref?: Ref<ThreadComposerHandle>
}
