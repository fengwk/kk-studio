import type { ThreadCommand } from '@/features/chat/runtime/thread-panel/thread-commands'
import type { TranslationKey } from '@/shared/i18n'
import type { BranchDraft } from '@/features/chat/branch-draft'
import {
  isBoundTarget,
  isDraftHistoryTarget,
  type PaneTarget,
} from '@/features/chat/runtime/agent-pane'
import type {
  AgentPaneCapabilities,
  BranchRequestInput,
  PaneInteraction,
  RenameKind,
} from '@/features/chat/runtime/root-thread/types'

export interface PaneCommandContext {
  target: PaneTarget
  capabilities?: AgentPaneCapabilities
  t: (key: TranslationKey) => string
  onFocus?: () => void
  onOpenIde?: (options: { mode: 'files' | 'shell' }) => void
  activeDraft: BranchDraft | null
  currentSessionId: string | null
  historySessionId: string | null
  rootEntryId: string | null
  boundThreadName: string | null
  hasPendingOperation: () => boolean
  setActionError: (message: string | null) => void
  setInteraction: (next: PaneInteraction) => void
  setLocalDraft: (update: (current: BranchDraft | null) => BranchDraft | null) => void
  changeTarget: (next: PaneTarget, draft?: BranchDraft | null) => boolean
  requestBranch: (input: BranchRequestInput) => boolean
  openRenameForTarget: (kind: RenameKind) => void
  setBoundYolo: (enabled: boolean) => void
  boundYoloEnabled: boolean
  toggleDebugView: () => void
  runControllerCommand: (command: ThreadCommand) => void
}

/** 面板命令菜单（ThreadCommand）到本 pane 动作的唯一分发点。 */
export function handlePaneCommand(ctx: PaneCommandContext, command: ThreadCommand): void {
  const { target, capabilities, t, setActionError, setInteraction } = ctx
  ctx.onFocus?.()
  if (command.disabled) {
    if (command.disabledReason) {
      setActionError(command.disabledReason)
    }
    return
  }
  switch (command.id) {
    case 'thread':
      if (capabilities?.allowBranching === false) {
        setActionError(t('ai.runtime.action.branchingDisabled'))
        return
      }
      if (ctx.hasPendingOperation()) {
        setActionError(t('ai.runtime.action.operationPending'))
        return
      }
      setInteraction('thread-sessions')
      return
    case 'history':
      if (capabilities?.allowBranching === false) {
        setActionError(t('ai.runtime.action.branchingDisabled'))
        return
      }
      if (ctx.hasPendingOperation()) {
        setActionError(t('ai.runtime.action.operationPending'))
        return
      }
      if (ctx.historySessionId == null) {
        setActionError(t('ai.runtime.action.threadNotLoaded'))
        return
      }
      setInteraction('history')
      return
    case 'new':
      if (capabilities?.readOnly) {
        return
      }
      if (capabilities?.allowBranching === false) {
        setActionError(t('ai.runtime.action.branchingDisabled'))
        return
      }
      if (capabilities?.allowNewSession === false) {
        if (ctx.currentSessionId && ctx.rootEntryId) {
          ctx.requestBranch({
            sessionId: ctx.currentSessionId,
            startEntryId: ctx.rootEntryId,
            sourceLabel: ctx.boundThreadName,
          })
          return
        }
        if (!ctx.currentSessionId) {
          ctx.changeTarget({ kind: 'NEW_SESSION_DRAFT' }, ctx.activeDraft)
        }
        return
      }
      ctx.changeTarget({ kind: 'NEW_SESSION_DRAFT' }, ctx.activeDraft)
      return
    case 'agent':
      if (capabilities?.allowSwitchAgent === false) {
        setActionError(t('ai.runtime.action.agentSwitchDisabled'))
        return
      }
      setInteraction('agent')
      return
    case 'yolo':
      if (isBoundTarget(target)) {
        ctx.setBoundYolo(!ctx.boundYoloEnabled)
      } else {
        ctx.setLocalDraft((current) =>
          current ? { ...current, yoloEnabled: !current.yoloEnabled } : current,
        )
      }
      return
    case 'debug':
      // 已绑定 Thread 与本地分支/会话 fork 草稿都从各自的 Debug 视图退出/进入：草稿没有绑定
      // Thread，Debug 视图以只读覆盖方式展示（绝不为此创建 Thread）。
      if (isBoundTarget(target) || isDraftHistoryTarget(target)) {
        ctx.toggleDebugView()
      }
      return
    case 'subagent':
      if (isBoundTarget(target)) {
        setInteraction('subagent')
      }
      return
    case 'shortcuts':
      setInteraction('shortcuts')
      return
    case 'rename-session':
      ctx.openRenameForTarget('session')
      return
    case 'rename-thread':
      ctx.openRenameForTarget('thread')
      return
    case 'goal':
      setInteraction('goal')
      return
    case 'compact':
    case 'stop':
      ctx.runControllerCommand(command)
      return
    case 'files':
      ctx.onOpenIde?.({ mode: 'files' })
      return
    case 'shell':
      ctx.onOpenIde?.({ mode: 'shell' })
      return
    case 'models':
    case 'upload':
      return
  }
}
