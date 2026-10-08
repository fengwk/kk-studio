import { useCallback } from 'react'
import { useI18n } from '@/shared/i18n'
import {
  ThreadPanel,
  ThreadShortcutsPanel,
  ThreadStatusFooter,
} from '@/features/ai/runtime'
import { ThreadPane } from '@/features/ai/runtime/ThreadPane'
import { RootThreadControlArea } from '@/features/ai/runtime/RootThreadControlArea'
import { ActiveThreadTree } from '@/features/ai/runtime/ActiveThreadTree'
import { useActiveThreadTree } from '@/features/ai/runtime/useActiveThreadTree'
import { AgentSelectionPanel, SelectionPanel } from '@/features/ai/chat/SelectionPanel'
import { HistoryTree } from '@/features/ai/chat/HistoryTree'
import {
  EntryBranchContext,
  branchRequestFromEndEntry,
} from '@/features/ai/runtime/thread-panel/entry-branch-context'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { NameRenamePanel } from '@/features/ai/runtime/thread-panel/NameRenamePanel'
import { BranchGoalPanel } from '@/features/ai/runtime/thread-panel/BranchGoalPanel'
import { formatThreadStatusLabel } from '@/features/ai/runtime/thread-panel/thread-status-format'
import {
  useRootThreadControl,
  type AgentPaneCapabilities,
  type AgentPaneDefaults,
  type BranchRequestInput,
  type PaneReport,
} from '@/features/ai/runtime/useRootThreadControl'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import type { ThreadProjection } from '@/features/ai/runtime/useThreadProjection'
import type { useThreadNavigation } from '@/features/ai/runtime/useThreadNavigation'

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
}: {
  owner?: AgentRuntimeOwnerDTO
  paneId: string
  agents: AgentDefinitionDTO[]
  environments: EnvironmentCardDTO[]
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
    target,
    setTarget,
    projection,
    covered,
    focusTarget,
    onFocusTargetChange,
  })
  const interactionPanel = renderInteractionPanel()
  const onDismissActionError = () => {
    pane.dismissActionError()
    pane.branchPanel.dismissYoloError()
    pane.controller.dismissActionError()
  }

  // 分支名不再在 pane 内重复展示（顶栏面包屑承载身份）；这里只保留状态标签判定。
  const boundThreadName = pane.boundBranchName

  // Debug 主视图接管主滚动区时，输入/审批/分支控制面整体隐藏（保持挂载以保留草稿与绑定）。
  // 已绑定 Thread 与本地分支草稿共用同一份视图状态：草稿没有绑定 Thread，但 Debug
  // 预览走会话级 branch preview，因此同样以整 pane 覆盖的方式展示。
  const debugActive = pane.boundViews.mode === 'debug'

  const boundStatus = pane.target.kind === 'BOUND_THREAD' ? pane.controller.thread?.status : null
  const boundWorkingLabel = boundStatus === 'QUEUED'
    || boundStatus === 'WAITING_APPROVAL'
    || boundStatus === 'TOOL_WAITING_APPROVAL'
    ? formatThreadStatusLabel(boundStatus, t)
    : undefined

  // 自动活跃树只挂执行根面板；根自身不重复出一行。查询由本组件单点持有。
  const boundIsRoot = boundThreadName != null && pane.controller.thread?.parentThreadId == null
  const boundThreadId = pane.target.kind === 'BOUND_THREAD' ? pane.target.threadId : null
  const tree = useActiveThreadTree(boundIsRoot ? boundThreadId : null)
  const composer = { ...pane.composer, interactionPanel, suspended: covered }

  const content = pane.target.kind === 'BOUND_THREAD'
    ? (
      <ThreadPane
        projection={pane.controller}
        environments={environments}
        activity={{
          working: pane.controller.working || pane.composer.pending,
          workingLabel: boundWorkingLabel,
          actionError: pane.error,
          onDismissActionError,
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
              tree={tree}
              hidden={debugActive}
            />
          )}
        views={{
          mainView: pane.boundViews.mainView,
          initialConversationScrollTop: pane.boundViews.initialConversationScrollTop,
        }}
      />
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
          emptyText: pane.target.kind === 'NEW_THREAD_DRAFT' && pane.draftAtRoot ? t('ai.chat.branch.draftEmpty') : undefined,
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
          actionError: pane.error,
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

  return (
    <EntryBranchContext.Provider value={canBranchFromConversation ? entryBranchRequest : null}>
      {content}
      {pane.pendingAcceptance ? (
        <div className="thread-acceptance-retry">
          {pane.pendingAcceptance.unknownOutcome ? (
            <button type="button" className="btn-primary" onClick={pane.retryAcceptance}>
              {t('shared.conflict.retry')}
            </button>
          ) : null}
          <button type="button" className="ghost-btn" onClick={pane.abandonPendingAcceptance}>
            {t('shared.cancel')}
          </button>
        </div>
      ) : null}
      {pane.draftRestoreError ? (
        <div className="thread-acceptance-retry" data-testid="draft-restore-retry">
          <span className="thread-acceptance-retry-text">{pane.draftRestoreError}</span>
          <button
            type="button"
            className="btn-primary"
            disabled={pane.pending}
            onClick={pane.retryDraftRestore}
          >
            {t('ai.runtime.action.retryDraftRestore')}
          </button>
        </div>
      ) : null}
      {pane.pendingMessage && pane.pendingMessage.unknownOutcome ? (
        <div className="thread-acceptance-retry" data-testid="bound-pending-controls">
          <button
            type="button"
            className="btn-primary"
            disabled={pane.controller.pending}
            onClick={pane.retryPendingMessage}
          >
            {t('shared.conflict.retry')}
          </button>
          <button
            type="button"
            className="ghost-btn"
            disabled={pane.controller.pending}
            onClick={pane.abandonPendingMessage}
          >
            {t('shared.cancel')}
          </button>
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

  function renderInteractionPanel() {
    if (pane.interaction === 'rename-session' || pane.interaction === 'rename-thread') {
      if (pane.renameTarget == null) {
        return null
      }
      const renameTarget = pane.renameTarget
      const sessionTitle = t('ai.runtime.rename.sessionTitle')
      const threadTitle = t('ai.runtime.rename.threadTitle')
      return (
        <NameRenamePanel
          title={renameTarget.kind === 'session' ? sessionTitle : threadTitle}
          initialName={renameTarget.name}
          busy={pane.renameBusy}
          pending={pane.renamePending}
          error={pane.renameError}
          onSubmit={(name) => pane.submitRename(name)}
          onClose={pane.closeRename}
        />
      )
    }
    if (pane.interaction === 'agent') {
      if (capabilities?.allowSwitchAgent === false) {
        return null
      }
      return (
        <AgentSelectionPanel
          agents={agents.map((agent) => ({ name: agent.name, description: agent.description }))}
          selectedAgentName={pane.activeDraft?.agentName}
          selectionPending={pane.pending}
          onClose={pane.closeInteraction}
          onSelect={pane.selectAgent}
        />
      )
    }
    if (pane.interaction === 'shortcuts') {
      return <ThreadShortcutsPanel onClose={pane.closeInteraction} />
    }
    if (pane.interaction === 'goal') {
      return (
        <BranchGoalPanel
          goal={pane.boundGoal}
          progress={pane.boundGoalProgress}
          busy={pane.pending}
          readOnly={Boolean(capabilities?.readOnly)}
          draftText={pane.goalDraft ?? undefined}
          onDraftTextChange={pane.setGoalDraft}
          onSubmitGoal={(goalText) => pane.submitGoal(goalText)}
          onClearGoal={() => pane.clearGoal()}
          onClose={pane.closeInteraction}
        />
      )
    }
    if (pane.interaction === 'history') {
      if (capabilities?.allowBranching === false) {
        return null
      }
      return (
        <HistoryTree
          entries={pane.treeEntries}
          headEntryId={
            pane.target.kind === 'BOUND_THREAD'
              ? pane.controller.thread?.headEntryId ?? null
              : pane.target.kind === 'NEW_THREAD_DRAFT'
                ? pane.target.startEntryId
                : null
          }
          loading={pane.treeEntriesLoading}
          queryError={pane.treeEntriesError}
          onClose={pane.closeInteraction}
          onFork={pane.requestBranchFromEntry}
        />
      )
    }
    if (pane.interaction === 'thread-sessions') {
      if (capabilities?.allowBranching === false) {
        return null
      }
      return (
        <SelectionPanel
          title={t('ai.chat.selectSession')}
          items={pane.sessions.map((session) => pane.sessionSelectionItem(session))}
          loading={pane.sessionsLoading}
          emptyText={t('ai.chat.noSessions')}
          renameLabel={t('ai.runtime.rename.titleAria')}
          onClose={pane.closeInteraction}
          onRename={(id) => {
            const session = pane.sessions.find((item) => item.sessionId === id)
            if (session) {
              pane.openRenameWithBackTo('session', session.sessionId, session.name, 'thread-sessions')
            }
          }}
          onSelect={(id) => {
            const session = pane.sessions.find((item) => item.sessionId === id)
            if (session) {
              pane.selectSession(session)
            }
          }}
        />
      )
    }
    if (pane.interaction === 'thread-threads') {
      if (capabilities?.allowBranching === false) {
        return null
      }
      return (
        <SelectionPanel
          title={t('ai.chat.selectThread')}
          items={pane.threads.map((thread) => pane.threadSelectionItem(thread))}
          loading={pane.threadsLoading}
          emptyText={t('ai.chat.noThreads')}
          renameLabel={t('ai.runtime.rename.titleAria')}
          onClose={() => {
            pane.openInteraction('thread-sessions')
          }}
          onRename={(id) => {
            const thread = pane.threads.find((item) => item.threadId === id)
            if (thread) {
              pane.openRenameWithBackTo('thread', thread.threadId, thread.name, 'thread-threads')
            }
          }}
          onSelect={(id) => {
            const thread = pane.threads.find((item) => item.threadId === id)
            if (thread) {
              pane.selectThread(thread)
            }
          }}
        />
      )
    }
    return null
  }
}
