import {
  ChatPanel,
  ThreadEventDetail,
  ThreadPanel,
  ThreadShortcutsPanel,
  ThreadStatusFooter,
} from '@/features/ai/runtime'
import { AgentSelectionPanel, SelectionPanel } from '@/features/ai/chat/SelectionPanel'
import { EnvironmentWorkspacePanel } from '@/features/ai/chat/EnvironmentWorkspacePanel'
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import {
  useAgentPaneController,
  type AgentPaneDefaults,
} from '@/features/ai/runtime/useAgentPaneController'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { useI18n } from '@/shared/i18n'

export type { AgentPaneDefaults }

export function AgentPane({
  owner,
  paneId,
  agents,
  environments = [],
  defaults = {},
  focused = false,
  onFocus,
}: {
  owner: AgentRuntimeOwnerDTO
  paneId: string
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  defaults?: AgentPaneDefaults
  focused?: boolean
  onFocus?: () => void
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
  })
  const interactionPanel = renderInteractionPanel()
  const onDismissActionError = () => {
    pane.dismissActionError()
    pane.branchPanel.dismissYoloError()
    pane.controller.dismissActionError()
  }

  const content = pane.target.kind === 'BOUND_THREAD'
    ? (
      <ChatPanel
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
          widgets: pane.boundViews.selectedRecord
            ? (
              <ThreadEventDetail
                record={pane.boundViews.selectedRecord}
                onClose={() => pane.boundViews.selectEvent(null)}
              />
            )
            : null,
          onDecideTaskApproval: pane.onDecideTaskApproval,
          actionError: pane.error,
          onDismissActionError,
        }}
      />
    )
    : (
      <ThreadPanel
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
              environment={pane.environment}
              environmentReady={pane.environmentReady}
              gitBranch={pane.gitBranch}
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
      <ConflictPresenter
        conflict={pane.conflict}
        onRefresh={() => void pane.refreshPaneProjection()}
        onRetry={pane.pendingAcceptance?.unknownOutcome ? pane.retryAcceptance : undefined}
        onClose={pane.dismissConflict}
      />
    </section>
  )

  function renderInteractionPanel() {
    if (pane.interaction === 'agent') {
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
    if (pane.interaction === 'environment') {
      return (
        <EnvironmentWorkspacePanel
          environments={environments}
          current={pane.environment}
          pending={pane.pending}
          onClose={pane.closeInteraction}
          onSelect={pane.selectEnvironment}
        />
      )
    }
    if (pane.interaction === 'shortcuts') {
      return <ThreadShortcutsPanel onClose={pane.closeInteraction} />
    }
    if (pane.interaction === 'tree') {
      return (
        <HistoryBranchPanel
          entries={pane.treeEntries}
          currentHeadEntryId={
            pane.target.kind === 'BOUND_THREAD'
              ? pane.controller.thread?.headEntryId ?? null
              : pane.target.kind === 'ENTRY_DRAFT'
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
      return (
        <SelectionPanel
          title={t('ai.chat.selectSession')}
          items={pane.sessions.map((session) => pane.sessionSelectionItem(session))}
          loading={pane.sessionsLoading}
          emptyText={t('ai.chat.noSessions')}
          onClose={pane.closeInteraction}
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
      return (
        <SelectionPanel
          title={t('ai.chat.selectThread')}
          items={pane.threads.map((thread) => pane.threadSelectionItem(thread))}
          loading={pane.threadsLoading}
          emptyText={t('ai.chat.noThreads')}
          onClose={() => {
            pane.openInteraction('thread-sessions')
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
