import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ChatPanel,
  type ChatPanelActivityInput,
  type ChatPanelComposerInput,
  type ChatPanelFooterInput,
  type ChatPanelLabels,
  type ChatPanelTranscriptInput,
  type ThreadMessageReplay,
  type ThreadCommand,
  useAgentThreadController,
} from '@/features/ai/runtime'
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import {
  AgentSelectionModal,
  EnvironmentSelectionModal,
  SelectionListModal,
} from '@/features/ai/chat/SelectionListModal'
import { type PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import {
  canRebindThread,
  toThreadSelectionItem,
} from '@/features/ai/chat/chat-session-picker'
import { branchTarget } from '@/features/ai/chat/session-entry-tree'
import { BOUND_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { errorMessage } from '@/features/ai/chat/chat-workspace-pane/pane-errors'
import { toThreadUsageSummary } from '@/features/ai/chat/chat-workspace-pane/usage-adapter'
import { useChatSessionPicker } from '@/features/ai/chat/useChatSessionPicker'
import { useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { isConflictError, isNotFoundError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { translate, useI18n } from '@/shared/i18n'

/** 409 = stale executionEpoch or non-quiescent Thread; never swallow it silently. */
function rebindErrorMessage(error: unknown): string {
  if (isConflictError(error)) {
    return translate('ai.runtime.action.rebindConflict', {
      error: errorMessage(error, translate('ai.catalog.validation.conflict')),
    })
  }
  return errorMessage(error, translate('ai.runtime.action.rebindFailed'))
}

export function BoundThreadPane({
  chatId,
  agents,
  environments = [],
  agentName,
  environmentName,
  yoloEnabled,
  settingsPending,
  paneId,
  threadId,
  focused,
  sessionSort,
  threadSort,
  onFocus,
  onThreadChange,
  onSessionSortChange,
  onThreadSortChange,
  onAgentChange,
  onEnvironmentChange,
  onYoloChange,
  initialReplay,
  onReplayInitialized,
}: {
  chatId: string
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  agentName: string
  environmentName: string | null
  yoloEnabled: boolean
  settingsPending: boolean
  paneId: string
  threadId: string
  focused: boolean
  sessionSort: PaneSortPreference
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onSessionSortChange: (sort: PaneSortPreference) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  onAgentChange: (agentName: string) => Promise<void>
  onEnvironmentChange: (environmentName: string | null) => Promise<void>
  onYoloChange: (yoloEnabled: boolean) => Promise<void>
  initialReplay?: ThreadMessageReplay
  onReplayInitialized?: () => void
}) {
  const { t } = useI18n()
  // sessionId is not persisted on pane; resolve from the Thread head after load.
  const controller = useAgentThreadController(
    threadId,
    initialReplay?.content ?? '',
    initialReplay,
    { agentName, environmentName, yoloEnabled },
  )
  const sessionId = controller.sessionId
  const queryClient = useQueryClient()
  // Session whose Entry Tree the history panel is browsing: current Session for /tree, the
  // picked Session for /session. Both end in the same PUT /head on the current Thread.
  const [historySessionId, setHistorySessionId] = useState<string | null>(null)
  const [sessionModalOpen, setSessionModalOpen] = useState(false)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [environmentModalOpen, setEnvironmentModalOpen] = useState(false)
  const [branchDraft, setBranchDraft] = useState('')
  const [rebindBlockedReason, setRebindBlockedReason] = useState<string | null>(null)
  const [threadAssociationPending, setThreadAssociationPending] = useState(false)
  const sessionPicker = useChatSessionPicker(sessionModalOpen, sessionSort)
  const threadPicker = useChatThreadPicker(chatId, threadModalOpen, threadSort)
  const rebindable = canRebindThread(controller.thread)

  useEffect(() => {
    if (initialReplay) {
      onReplayInitialized?.()
    }
  }, [initialReplay, onReplayInitialized])

  useEffect(() => {
    if (isNotFoundError(controller.messagesError)) {
      onThreadChange(null)
    }
  }, [controller.messagesError, onThreadChange])

  useEffect(() => {
    if (branchDraft) {
      controller.setDraft(branchDraft)
      setBranchDraft('')
    }
    // Only reapply explicit branch draft once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [branchDraft])

  const sessionEntriesQuery = useQuery({
    queryKey: queryKeys.sessions.entries(historySessionId ?? ''),
    queryFn: () => harnessService.listSessionEntries(historySessionId!),
    enabled: Boolean(historySessionId),
  })
  const rebindMutation = useMutation({
    mutationFn: (entry: HarnessSessionEntryDTO) => {
      const target = branchTarget(entry)
      if (!target.headEntryId) {
        return Promise.reject(new Error(t('ai.runtime.action.rootNotBranchable')))
      }
      if (!controller.thread) {
        return Promise.reject(new Error(t('ai.runtime.action.threadNotLoaded')))
      }
      return harnessService.updateThreadHead(threadId, {
        headEntryId: target.headEntryId,
        expectedExecutionEpoch: controller.thread.executionEpoch,
      })
    },
    onSuccess: async (_thread, entry) => {
      setHistorySessionId(null)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.list }),
      ])
      setBranchDraft(branchTarget(entry).draft)
    },
  })

  const threadItems = threadPicker.items.map((thread) => toThreadSelectionItem(thread, threadSort))

  async function selectThread(selectedThreadId: string) {
    setThreadAssociationPending(true)
    try {
      if (threadPicker.scope === 'global') {
        await chatService.associateThread(chatId, selectedThreadId)
        await queryClient.invalidateQueries({ queryKey: queryKeys.threads.list })
      }
      setThreadModalOpen(false)
      onThreadChange(selectedThreadId)
    } catch (error) {
      setRebindBlockedReason(errorMessage(error, t('ai.runtime.action.associateThreadFailed')))
    } finally {
      setThreadAssociationPending(false)
    }
  }

  async function selectAgent(selectedAgentName: string) {
    if (settingsPending) {
      return
    }
    setRebindBlockedReason(null)
    try {
      await onAgentChange(selectedAgentName)
      setAgentModalOpen(false)
    } catch (error) {
      setRebindBlockedReason(errorMessage(error, t('ai.runtime.action.updateAgentFailed')))
    }
  }

  async function selectEnvironment(environmentName: string | null): Promise<boolean> {
    if (settingsPending) {
      return false
    }
    setRebindBlockedReason(null)
    try {
      await onEnvironmentChange(environmentName)
      setEnvironmentModalOpen(false)
      return true
    } catch (error) {
      setRebindBlockedReason(errorMessage(error, t('ai.runtime.action.updateEnvironmentFailed')))
      return false
    }
  }

  async function toggleYolo() {
    if (settingsPending) {
      return
    }
    setRebindBlockedReason(null)
    try {
      await onYoloChange(!yoloEnabled)
    } catch (error) {
      setRebindBlockedReason(errorMessage(error, t('ai.runtime.action.updateYoloFailed')))
    }
  }

  /** /session and /tree both relocate the current Thread, so both need a quiescent Thread. */
  function openRebindTarget(open: () => void) {
    if (!rebindable) {
      setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
      return
    }
    setRebindBlockedReason(null)
    open()
  }

  function handleCommand(command: ThreadCommand) {
    onFocus()
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'session':
        openRebindTarget(() => setSessionModalOpen(true))
        return
      case 'thread':
        setThreadModalOpen(true)
        return
      case 'agent':
        setAgentModalOpen(true)
        return
      case 'environment':
        setEnvironmentModalOpen(true)
        return
      case 'yolo':
        void toggleYolo()
        return
      case 'tree':
        openRebindTarget(() => setHistorySessionId(sessionId || null))
        return
      case 'new':
        // Detach the pane so the next send creates a fresh Chat-bound Thread atomically.
        controller.setDraft('')
        onThreadChange(null)
        return
      default:
        controller.runCommand(command)
    }
  }

  const labels: ChatPanelLabels = {
    agentName: controller.runtimeLabels.agentName,
    providerName: controller.runtimeLabels.providerName,
    modelName: controller.runtimeLabels.modelName,
    variantName: controller.runtimeLabels.variantName,
    environmentName: controller.runtimeLabels.environmentName,
    contextWindow: controller.runtimeLabels.contextWindow,
  }
  const transcript: ChatPanelTranscriptInput = {
    timeline: controller.timeline,
    bodyRef: controller.bodyRef,
    loading: controller.messagesLoading,
    error: controller.messagesError,
  }
  const composer: ChatPanelComposerInput = {
    draft: controller.draft,
    pending: controller.pending || settingsPending,
    disabled: controller.disabled || settingsPending,
    onDraftChange: controller.setDraft,
    onSubmit: () => {
      void controller.submitMessage()
    },
    onCommand: handleCommand,
    commands: BOUND_PANE_COMMANDS,
  }
  const footer: ChatPanelFooterInput = {
    yoloEnabled,
    usage: controller.observability.usage
      ? toThreadUsageSummary(controller.observability.usage)
      : undefined,
    onAgentClick: () => {
      onFocus()
      setAgentModalOpen(true)
    },
    onModelClick: undefined,
    onVariantClick: undefined,
    onEnvironmentClick: () => {
      onFocus()
      setEnvironmentModalOpen(true)
    },
  }
  const activity: ChatPanelActivityInput = {
    working: controller.working,
    actionError: rebindBlockedReason ?? controller.actionError,
    onDismissActionError: () => {
      setRebindBlockedReason(null)
      controller.dismissActionError()
    },
  }

  return (
    <section
      className={`chat-pane ${focused ? 'focused' : ''}`}
      onMouseDown={onFocus}
      data-pane-id={paneId}
    >
      <ChatPanel
        labels={labels}
        transcript={transcript}
        composer={composer}
        footer={footer}
        activity={activity}
      />
      {historySessionId ? (
        <HistoryBranchPanel
          entries={sessionEntriesQuery.data ?? []}
          currentHeadEntryId={controller.thread?.headEntryId}
          loading={sessionEntriesQuery.isLoading}
          queryError={sessionEntriesQuery.error}
          pending={rebindMutation.isPending}
          rebindError={rebindMutation.error ? rebindErrorMessage(rebindMutation.error) : null}
          onClose={() => {
            setHistorySessionId(null)
            rebindMutation.reset()
          }}
          onRebind={(entry) => rebindMutation.mutate(entry)}
        />
      ) : null}
      {/* /session picks the target Session, then its Entry Tree supplies the new head. */}
      <SelectionListModal
        open={sessionModalOpen}
        title={t('ai.chat.selectSession')}
        items={sessionPicker.sessionItems}
        sort={sessionSort}
        onSortChange={onSessionSortChange}
        emptyText={t('ai.chat.noSessions')}
        onClose={() => setSessionModalOpen(false)}
        onSelect={(selectedSessionId) => {
          if (!sessionPicker.findSession(selectedSessionId)) {
            return
          }
          setSessionModalOpen(false)
          setHistorySessionId(selectedSessionId)
        }}
      />
      {/* /thread only switches which Thread this pane shows; no Thread is mutated. */}
      <SelectionListModal
        open={threadModalOpen}
        title={t('ai.chat.selectThread')}
        items={threadItems}
        sort={threadSort}
        onSortChange={onThreadSortChange}
        scope={threadPicker.scope}
        onScopeChange={threadPicker.setScope}
        loading={threadPicker.isLoading}
        hasMore={threadPicker.hasNextPage}
        loadingMore={threadPicker.isFetchingNextPage}
        onLoadMore={() => {
          void threadPicker.loadMore()
        }}
        selectionPending={threadAssociationPending}
        emptyText={t('ai.chat.noThreads')}
        onClose={() => setThreadModalOpen(false)}
        onSelect={selectThread}
      />
      <AgentSelectionModal
        open={agentModalOpen}
        selectionPending={settingsPending}
        agents={agents.map((agent) => ({
          name: agent.name,
          description: agent.description,
        }))}
        onClose={() => setAgentModalOpen(false)}
        onSelect={selectAgent}
      />
      <EnvironmentSelectionModal
        open={environmentModalOpen}
        environments={environments}
        selectedEnvironmentName={environmentName}
        selectionPending={settingsPending}
        onClose={() => setEnvironmentModalOpen(false)}
        onSelect={(environmentName) => {
          void selectEnvironment(environmentName)
        }}
      />
    </section>
  )
}
