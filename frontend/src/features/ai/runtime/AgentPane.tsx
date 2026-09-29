import {
  ChatPanel,
  ThreadPanel,
  ThreadShortcutsPanel,
  ThreadStatusFooter,
} from '@/features/ai/runtime'
import { AgentSelectionPanel, SelectionPanel } from '@/features/ai/chat/SelectionPanel'
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import {
  useAgentPaneController,
  type AgentPaneCapabilities,
  type AgentPaneDefaults,
} from '@/features/ai/runtime/useAgentPaneController'
import { Pencil as PencilIcon } from 'lucide-react'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import { useI18n } from '@/shared/i18n'
import { NameRenamePanel } from '@/features/ai/runtime/thread-panel/NameRenamePanel'
import { BranchGoalPanel } from '@/features/ai/runtime/thread-panel/BranchGoalPanel'
import { formatThreadStatusLabel } from '@/features/ai/runtime/thread-panel/thread-status-format'

import type { ComposerPart } from '@/features/ai/composer/composer-parts'

export type { AgentPaneCapabilities, AgentPaneDefaults }

export function AgentPane({
  owner,
  paneId,
  agents,
  environments = [],
  defaults = {},
  focused = false,
  onFocus,
  initialTarget,
  capabilities,
  onSubmitInstruction,
  onStop,
}: {
  owner?: AgentRuntimeOwnerDTO
  paneId: string
  agents: AgentDefinitionDTO[]
  environments?: EnvironmentCardDTO[]
  defaults?: AgentPaneDefaults
  focused?: boolean
  onFocus?: () => void
  initialTarget?: PaneTarget
  capabilities?: AgentPaneCapabilities
  onSubmitInstruction?: (text: string, parts: ComposerPart[]) => Promise<void> | void
  onStop?: () => Promise<void> | void
}) {
  const { t } = useI18n()
  const pane = useAgentPaneController({
    owner,
    paneId,
    agents,
    environments,
    defaults,
    focused,
    onFocus,
    initialTarget,
    capabilities,
    onSubmitInstruction,
    onStop,
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
  const boundWorkingLabel = boundStatus === 'WAITING_CHILDREN' || boundStatus === 'QUEUED'
    ? formatThreadStatusLabel(boundStatus, t)
    : undefined

  const content = pane.target.kind === 'BOUND_THREAD'
    ? (
      <ChatPanel
        heading={renderBoundThreadHeading(boundThreadName)}
        labels={pane.boundLabels}
        transcript={pane.buildBoundThreadTranscript({
          controller: pane.controller,
          threadId: pane.target.threadId,
          initialConversationScrollTop: pane.boundViews.initialConversationScrollTop,
        })}
        mainView={pane.boundViews.mainView}
        composer={{ ...pane.composer, interactionPanel }}
        activity={{
          working: pane.controller.working,
          workingLabel: boundWorkingLabel,
          actionError: pane.error,
          onDismissActionError,
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
        composer={{ ...pane.composer, interactionPanel }}
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
    <section
      className={`chat-pane ${focused ? 'focused' : ''}`}
      data-pane-id={paneId}
      onMouseDown={onFocus}
    >
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
    </section>
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
          disabled={!owner || owner.type === 'ISSUE_AGENT' || name == null || pane.renamePending || Boolean(capabilities?.readOnly)}
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
      const target = pane.renameTarget
      const sessionTitle = t('ai.runtime.rename.sessionTitle')
      const threadTitle = t('ai.runtime.rename.threadTitle')
      return (
        <NameRenamePanel
          title={target.kind === 'session' ? sessionTitle : threadTitle}
          initialName={target.name}
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
