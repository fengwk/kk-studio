import { useI18n } from '@/shared/i18n'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import type { BranchDraft } from '@/features/chat/branch-draft'
import { isBoundTarget, type PaneTarget } from '@/features/chat/runtime/agent-pane'
import type {
  AgentPaneCapabilities,
  BranchRequestInput,
  PaneInteraction,
} from '@/features/chat/runtime/root-thread/types'
import type {
  HarnessSessionEntryDTO,
  HarnessSessionSummaryDTO,
  HarnessThreadSummaryDTO,
} from '@/shared/api/generated/contracts'

interface PaneNavigationOptions {
  owner: AgentRuntimeOwnerDTO | undefined
  target: PaneTarget
  capabilities?: AgentPaneCapabilities
  onRequestBranch?: (request: BranchRequestInput) => void
  boundThreadName: string | null
  boundSessionId: string | null
  activeDraft: BranchDraft | null
  threadNavigationSessionId: string | null
  setThreadNavigationSessionId: (sessionId: string | null) => void
  changeTarget: (next: PaneTarget, draft?: BranchDraft | null) => boolean
  hasPendingOperation: () => boolean
  setInteraction: (next: PaneInteraction) => void
  setActionError: (message: string | null) => void
}

/**
 * Pane 内的 Session/Thread 导航与两种“分叉”入口：
 * - 同一会话内分支（requestBranch*）：新建 Thread，落在 Chat workspace 选定的 pane，仍属当前 Chat；
 * - 新会话分叉（requestSessionForkFromEntry）：把切点前的历史复制进本 pane 的新会话草稿，
 *   首次输入时创建新 Session。
 */
export function usePaneNavigation(options: PaneNavigationOptions) {
  const {
    owner, target, capabilities, onRequestBranch, boundThreadName, boundSessionId, activeDraft,
    threadNavigationSessionId, setThreadNavigationSessionId, changeTarget, hasPendingOperation,
    setInteraction, setActionError,
  } = options
  const { t } = useI18n()

  /**
   * 统一的“从此处分支”入口：分支名称与目标 pane 由 Chat workspace 决定，
   * 本 pane 只请求，不本地改写自己的目标（新建 Thread 落在目标 pane）。
   * 无 owner/无 workspace 回调（如只读宿主）时保持此前的无操作语义。
   */
  function requestBranch(input: BranchRequestInput): boolean {
    if (owner == null || capabilities?.allowBranching === false) {
      setActionError(t('ai.runtime.action.branchingDisabled'))
      return false
    }
    // 没有 workspace 回调的宿主（只读嵌入）没有新建分支的去处，保持无操作。
    if (onRequestBranch == null) {
      return false
    }
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return false
    }
    setActionError(null)
    onRequestBranch(input)
    return true
  }

  /** 历史树行的分叉动作：只对 ROOT / 已关闭 TURN_END 可用（面板已禁用其它行）。 */
  function requestBranchFromEntry(entry: HarnessSessionEntryDTO): void {
    if (requestBranch({
      sessionId: entry.sessionId,
      startEntryId: entry.entryId,
      sourceLabel: boundThreadName,
    })) {
      setThreadNavigationSessionId(null)
      setInteraction(null)
    }
  }

  const historySourceThreadId = isBoundTarget(target)
    && threadNavigationSessionId == null
    && boundSessionId != null
    ? target.threadId
    : target.kind === 'FORK_SESSION_DRAFT' && threadNavigationSessionId == null
      ? target.sourceThreadId
      : null
  const canForkSession = Boolean(owner)
    && capabilities?.allowBranching !== false
    && !capabilities?.readOnly
    && historySourceThreadId != null

  /**
   * 历史树行的显式“从此处新建会话”动作：把当前来源执行根 Thread 与选定切点写入
   * 本 pane 的会话 fork 草稿（不预创建 Session/Thread）。源 Thread 继续独立运行，
   * 目标与本地草稿只改本 pane；创建仍由首次输入原子提交。
   */
  function requestSessionForkFromEntry(entry: HarnessSessionEntryDTO): void {
    if (!canForkSession || historySourceThreadId == null) {
      setActionError(t('ai.runtime.action.branchingDisabled'))
      return
    }
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return
    }
    if (changeTarget({
      kind: 'FORK_SESSION_DRAFT',
      sessionId: entry.sessionId,
      sourceThreadId: historySourceThreadId,
      startEntryId: entry.entryId,
    })) {
      setThreadNavigationSessionId(null)
      setInteraction(null)
    }
  }

  function selectSession(session: HarnessSessionSummaryDTO): void {
    if (!owner || capabilities?.allowBranching === false) {
      setActionError(t('ai.runtime.action.branchingDisabled'))
      return
    }
    setThreadNavigationSessionId(session.sessionId)
    setInteraction(session.threadCount === 0 ? 'history' : 'thread-threads')
  }

  function selectThread(thread: HarnessThreadSummaryDTO): void {
    if (!owner || capabilities?.allowBranching === false) {
      setActionError(t('ai.runtime.action.branchingDisabled'))
      return
    }
    changeTarget({ kind: 'BOUND_THREAD', threadId: thread.threadId }, activeDraft)
  }

  return {
    requestBranch,
    requestBranchFromEntry,
    requestSessionForkFromEntry,
    canForkSession,
    selectSession,
    selectThread,
  }
}
