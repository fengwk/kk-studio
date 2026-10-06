import { Pencil as PencilIcon } from 'lucide-react'
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
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { NameRenamePanel } from '@/features/ai/runtime/thread-panel/NameRenamePanel'
import { BranchGoalPanel } from '@/features/ai/runtime/thread-panel/BranchGoalPanel'
import { formatThreadStatusLabel } from '@/features/ai/runtime/thread-panel/thread-status-format'
import {
  useRootThreadControl,
  type AgentPaneCapabilities,
  type AgentPaneDefaults,
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
  target,
  setTarget,
  projection,
  navigation,
  covered,
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
  target: PaneTarget
  setTarget: (next: PaneTarget) => void
  projection: ThreadProjection
  navigation: ReturnType<typeof useThreadNavigation>
  covered: boolean
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
    target,
    setTarget,
    projection,
  })
  const interactionPanel = renderInteractionPanel()
  const onDismissActionError = () => {
    pane.dismissActionError()
    pane.branchPanel.dismissYoloError()
    pane.controller.dismissActionError()
  }

  // Bound Thread 主列顶部的名称标题；名称是主展示文本（绝不回退为 id）。
  const boundThreadName = pane.target.kind === 'BOUND_THREAD'
    && pane.controller.thread?.threadId === pane.target.threadId
    ? pane.controller.thread.name
    : null

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
        heading={renderBoundThreadHeading(boundThreadName)}
        activity={{
          working: pane.controller.working || pane.composer.pending,
          workingLabel: boundWorkingLabel,
          actionError: pane.error,
          onDismissActionError,
          widgets: boundIsRoot && boundThreadId != null
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
          messages: [],
          queuedMessages: [],
          bodyRef: pane.controller.bodyRef,
          loading: false,
          error: null,
        }}
        controls={(
          <RootThreadControlArea
            rootThreadId={null}
            composer={composer}
            messages={[]}
            queuedMessages={[]}
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

  return (
    <>
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
    </>
  )

  function renderBoundThreadHeading(name: string | null) {
    return (
      <header className="agent-pane-thread-heading">
        {name != null ? (
          <h2 className="agent-pane-thread-title" title={name}>{name}</h2>
        ) : (
          <h2 className="agent-pane-thread-title">{t('ai.runtime.rename.loadingName')}</h2>
        )}
        <button
          type="button"
          className="agent-pane-thread-rename"
          aria-label={t('ai.runtime.rename.titleAria')}
          title={t('ai.runtime.rename.titleAria')}
          disabled={name == null || pane.renamePending || Boolean(capabilities?.readOnly)}
          onClick={() => {
            if (name != null && pane.target.kind === 'BOUND_THREAD') {
              pane.renameThread(pane.target.threadId, name)
            }
          }}
        >
          <PencilIcon aria-hidden="true" />
        </button>
      </header>
    )
  }

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
    if (pane.interaction === 'tree') {
      if (capabilities?.allowBranching === false) {
        return null
      }
      return (
        <HistoryBranchPanel
          entries={pane.treeEntries}
          currentHeadEntryId={
            pane.target.kind === 'BOUND_THREAD'
              ? pane.controller.thread?.headEntryId ?? null
              : pane.target.kind === 'NEW_THREAD_DRAFT'
                ? pane.target.startEntryId
                : null
          }
          loading={pane.treeEntriesLoading}
          queryError={pane.treeEntriesError}
          onClose={pane.closeInteraction}
          onSelectEntry={pane.selectEntry}
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
