import { useCallback } from 'react'
import { useI18n } from '@/shared/i18n'
import {
  ThreadPanel,
  ThreadStatusFooter,
} from '@/features/thread/runtime/thread-panel'
import { ThreadPane } from '@/features/thread/runtime/panes/ThreadPane'
import { RootThreadControlArea } from '@/features/thread/runtime/panes/RootThreadControlArea'
import { RootInteractionsProvider } from '@/features/thread/runtime/interactions/root-interactions'
import { ActiveThreadTree } from '@/features/thread/runtime/panes/ActiveThreadTree'
import { useThreadPresentation, type ThreadPresentation } from './thread-presentation'
import { renderPaneInteraction } from '@/features/thread/runtime/panes/root-agent-pane-interactions'
import { Button } from '@/shared/ui/controls/Button'
import { useActiveThreadTree } from '@/features/thread/runtime/queries/useActiveThreadTree'
import {
  EntryBranchContext,
  branchRequestFromEndEntry,
} from '@/features/thread/runtime/thread-panel/entry-branch-context'
import {
  CompactionActionsContext,
  type CompactionActions,
} from '@/features/thread/runtime/thread-panel/compaction-actions-context'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { useRootThreadControl } from '@/features/thread/runtime/controllers/useRootThreadControl'
import { type AgentPaneCapabilities, type AgentPaneDefaults, type BranchRequestInput, type PaneReport } from '@/features/thread/runtime/root-thread/types'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import type { PaneTarget } from '@/features/thread/runtime/agent-pane'
import type { ThreadProjection } from '@/features/thread/runtime/useThreadProjection'
import type { useThreadNavigation } from '@/features/thread/runtime/controllers/useThreadNavigation'
import type {
  SessionSummaryLoader,
  SessionSummaryResolver,
} from '@/features/thread/runtime/root-thread/usePaneHistory'
import type {
  AgentDTO,
  EnvironmentDTO,
} from '@/shared/api/generated/contracts'

/**
 * 根控面板：唯一挂载根草稿、上传与人工执行 Hook 的地方，并保留根交互汇聚、
 * 活跃树与全部根控制面板。
 *
 * 只由父 `AgentPane` 在「目标未绑定 Thread（草稿）」或「身份已确认是执行根」时渲染；
 * 子代理目标与身份未确认时不渲染本组件，因此这些 Hook 不会在只读路径上出现。
 * 查看子代理期间本组件保持挂载（父面板把根层隐藏为 inert），草稿、上传、滚动与
 * 展开状态原地保留；被覆盖时 `covered` 使 Composer 失活（不抢焦点、不响应快捷键）。
 */
export function RootAgentPane({
  owner,
  paneId,
  agents,
  environments,
  defaults,
  focused,
  initialTarget,
  onTargetConsumed,
  capabilities,
  onRequestBranch,
  onReport,
  onValidateDraftName,
  target,
  setTarget,
  projection,
  navigation,
  covered,
  focusTarget,
  onFocusTargetChange,
  presentationSelected = true,
  onPresentation,
  onOpenIde,
  loadSessions,
  loadSession,
}: {
  owner?: AgentRuntimeOwnerDTO
  paneId: string
  agents: AgentDTO[]
  environments: EnvironmentDTO[]
  defaults: AgentPaneDefaults
  focused: boolean
  initialTarget?: PaneTarget
  onTargetConsumed?: (target: PaneTarget) => void
  capabilities?: AgentPaneCapabilities
  onRequestBranch?: (request: BranchRequestInput) => void
  onReport?: (report: PaneReport) => void
  onValidateDraftName?: (target: PaneTarget, name: string) => Promise<string | null>
  target: PaneTarget
  setTarget: (next: PaneTarget) => void
  projection: ThreadProjection
  navigation: ReturnType<typeof useThreadNavigation>
  covered: boolean
  focusTarget: PaneTarget | null
  onFocusTargetChange: (target: PaneTarget | null) => void
  presentationSelected?: boolean
  onPresentation?: (report: ThreadPresentation) => void
  onOpenIde?: (options: { mode: 'files' | 'shell' }) => void
  /** 宿主读取 Owner Session 摘要分页；未注入时 Session 导航入口不可用。 */
  loadSessions?: SessionSummaryLoader
  /** 宿主按 id 解析单个 Owner Session 摘要；未注入时分页窗口外的重命名不可解析。 */
  loadSession?: SessionSummaryResolver
}) {
  const { t } = useI18n()
  const pane = useRootThreadControl({
    owner,
    paneId,
    agents,
    environments,
    defaults,
    focused,
    initialTarget,
    onTargetConsumed,
    capabilities,
    onRequestBranch,
    onReport,
    onValidateDraftName,
    onOpenIde,
    target,
    setTarget,
    projection,
    covered,
    focusTarget,
    onFocusTargetChange,
    loadSessions,
    loadSession,
  })
  const onDismissActionError = () => {
    pane.dismissActionError()
    pane.branchPanel.dismissYoloError()
    pane.controller.dismissActionError()
  }

  // 分支名由顶栏面包屑承载身份，pane 内不重复展示；这里只保留状态标签判定。
  const boundThreadName = pane.boundBranchName

  // Debug 主视图接管主滚动区时，输入/审批/分支控制面整体隐藏（保持挂载以保留草稿与绑定）。
  // 已绑定 Thread 与本地分支草稿共用同一份视图状态：草稿没有绑定 Thread，其 Debug
  // 视图同样以整 pane 只读覆盖的方式展示。
  const debugActive = pane.boundViews.mode === 'debug'

  // 自动活跃树只挂执行根面板；根自身不重复出一行。查询由本组件单点持有。
  const boundIsRoot = boundThreadName != null && pane.controller.thread?.parentThreadId == null
  const boundThreadId = pane.target.kind === 'BOUND_THREAD' ? pane.target.threadId : null
  const tree = useActiveThreadTree(boundIsRoot ? boundThreadId : null)
  useThreadPresentation({
    views: pane.boundViews,
    projection: pane.controller,
    threadId: boundThreadId ?? '',
    selected: presentationSelected,
    active: !covered,
    onReport: onPresentation,
    // 已打开的交互面板仍接管输入；Debug 退出恢复读入口，不向隐藏 Composer 排队焦点意图。
    onRestoreFocus: pane.interaction == null ? pane.restoreComposerFocus : undefined,
  })
  const interactionPanel = renderPaneInteraction({
    pane,
    tree,
    boundThreadId,
    agents,
    capabilities,
    t,
  })
  // 环境归执行根：执行树里有正在运行的子代理时，切换操作同样作用于它们，提示只在此条件下出现。
  const environmentSwitchNotice = tree.activeCount > 0
    ? t('ai.runtime.composer.environmentSwitchNotice')
    : null
  const composer = {
    ...pane.composer,
    interactionPanel,
    suspended: covered,
    settings: pane.composer.settings == null ? undefined : {
      ...pane.composer.settings,
      environmentSwitchNotice,
    },
  }
  const content = pane.target.kind === 'BOUND_THREAD'
    ? (
      <RootInteractionsProvider
        rootThreadId={pane.target.threadId}
        approvalsVisible={!debugActive}
      >
        <ThreadPane
          projection={pane.controller}
          environments={environments}
          activity={{
            actionError: pane.interaction === 'agent' && interactionPanel != null ? null : pane.error,
            onDismissActionError,
            onStop: () => { void pane.controller.stopThread() },
            stopPending: pane.controller.stopPending || pane.controller.stopReplayPending,
            widgets: boundIsRoot && boundThreadId != null && !debugActive
              ? (
                <ActiveThreadTree
                  tree={tree}
                  currentThreadId={navigation.activeThreadId ?? boundThreadId}
                />
              )
              : undefined,
          }}
          controls={boundThreadId == null
            ? null
            : (
              <RootThreadControlArea
                  rootThreadId={boundThreadId}
                  composer={composer}
                  messages={pane.controller.timeline.messages}
                  queuedMessages={pane.controller.timeline.queuedMessages}
                  hidden={debugActive}
                />
            )}
          views={{
            mainView: pane.boundViews.mainView,
            initialConversationScrollTop: pane.boundViews.initialConversationScrollTop,
          }}
        />
      </RootInteractionsProvider>
    )
    : (
      <ThreadPanel
        heading={null}
        transcript={{
          messages: pane.draftTimeline.messages,
          queuedMessages: [],
          bodyRef: pane.controller.bodyRef,
          loading: pane.draftHistoryLoading,
          error: pane.draftHistoryError,
          errorText: pane.draftHistoryErrorText,
          onRetry: pane.retryDraftHistory,
          emptyText: pane.target.kind === 'NEW_THREAD_DRAFT' && pane.draftAtRoot
            ? t('ai.chat.branch.draftEmpty')
            : pane.target.kind === 'FORK_SESSION_DRAFT' && pane.draftAtRoot
              ? t('ai.chat.branch.forkSessionDraftEmpty')
              : undefined,
          initialScrollTop: pane.boundViews.initialConversationScrollTop,
        }}
        mainView={pane.boundViews.mainView}
        controls={(
          <RootThreadControlArea
              rootThreadId={null}
              composer={composer}
              messages={pane.draftTimeline.messages}
              queuedMessages={[]}
              hidden={debugActive}
            />
        )}
        activity={{
          working: false,
          actionError: pane.interaction === 'agent' && interactionPanel != null ? null : pane.error,
          onDismissActionError,
        }}
        slots={{
          footer: (
            <ThreadStatusFooter
              environment={
                pane.boundEnvironment
                  ? {
                      environmentId: pane.boundEnvironment.id,
                      environmentName: pane.boundEnvironment.name,
                    }
                  : null
              }
              environmentReady={pane.environmentReady}
            />
          ),
        }}
      />
    )

  const requestBranchRef = pane.requestBranch
  const branchSessionId = pane.target.kind === 'BOUND_THREAD' ? pane.currentSessionId : null
  const entryBranchRequest = useCallback(
    (endEntryId: string) => {
      const request = branchRequestFromEndEntry({
        endEntryId,
        sessionId: branchSessionId,
        sourceLabel: boundThreadName,
      })
      if (request != null) {
        requestBranchRef(request)
      }
    },
    [boundThreadName, branchSessionId, requestBranchRef],
  )
  const canBranchFromConversation = branchSessionId != null
    && pane.target.kind === 'BOUND_THREAD'
    && capabilities?.allowBranching !== false
    && !debugActive
    && pane.target.threadId === pane.controller.thread?.threadId

  // 压缩卡的动作只绑定当前主 Thread 的手动压缩入口；草稿目标没有可压缩的 Thread。
  const compactionActions: CompactionActions | null = pane.target.kind === 'BOUND_THREAD'
    ? {
        compact: () => { void pane.controller.compactThread() },
        compactPending: pane.controller.compactPending,
        availability: pane.compaction,
      }
    : null

  return (
    <EntryBranchContext.Provider value={canBranchFromConversation ? entryBranchRequest : null}>
      <CompactionActionsContext.Provider value={compactionActions}>
        {content}
      </CompactionActionsContext.Provider>
      {pane.pendingAcceptance ? (
        <div className="thread-acceptance-retry" hidden={debugActive} inert={debugActive}>
          {pane.pendingAcceptance.unknownOutcome ? (
            <Button onClick={pane.retryAcceptance}>
              {t('shared.conflict.retry')}
            </Button>
          ) : null}
          <Button variant="ghost" onClick={pane.abandonPendingAcceptance}>
            {t('shared.cancel')}
          </Button>
        </div>
      ) : null}
      {pane.pendingAcceptanceBlocked ? (
        <div
          className="thread-acceptance-retry"
          data-testid="pending-acceptance-blocked"
          role="status"
          hidden={debugActive}
          inert={debugActive}
        >
          <span className="thread-acceptance-retry-text">
            {t('ai.runtime.action.pendingAcceptanceBlocked')}
          </span>
        </div>
      ) : null}
      {pane.draftRestoreError ? (
        <div className="thread-acceptance-retry" data-testid="draft-restore-retry" hidden={debugActive} inert={debugActive}>
          <span className="thread-acceptance-retry-text">{pane.draftRestoreError}</span>
          <Button
            disabled={pane.pending}
            onClick={pane.retryDraftRestore}
          >
            {t('ai.runtime.action.retryDraftRestore')}
          </Button>
        </div>
      ) : null}
      {pane.pendingMessage && pane.pendingMessage.unknownOutcome ? (
        <div className="thread-acceptance-retry" data-testid="bound-pending-controls" hidden={debugActive} inert={debugActive}>
          <Button
            disabled={pane.controller.pending}
            onClick={pane.retryPendingMessage}
          >
            {t('shared.conflict.retry')}
          </Button>
          <Button
            variant="ghost"
            disabled={pane.controller.pending}
            onClick={pane.abandonPendingMessage}
          >
            {t('shared.cancel')}
          </Button>
        </div>
      ) : null}
      <ConflictPresenter
        conflict={pane.conflict}
        onRefresh={() => void pane.refreshPaneProjection()}
        onRetry={
          pane.pendingAcceptance?.unknownOutcome
            ? pane.retryAcceptance
            : pane.pendingMessage?.unknownOutcome
              ? pane.retryPendingMessage
              : undefined
        }
        onClose={pane.dismissConflict}
      />
    </EntryBranchContext.Provider>
  )
}
