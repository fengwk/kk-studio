import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  ChatPanel,
  type ChatPanelActivityInput,
  type ChatPanelComposerInput,
  type ChatPanelFooterInput,
  type ChatPanelLabels,
  type ChatPanelTranscriptInput,
  type CommandBatchReplay,
  type ThreadCommand,
  useAgentThreadController,
} from '@/features/ai/runtime'
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import {
  AgentSelectionModal,
  EnvironmentSelectionModal,
  SelectionListModal,
} from '@/features/ai/chat/SelectionListModal'
import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { BOUND_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { errorMessage } from '@/features/ai/chat/chat-workspace-pane/pane-errors'
import { extractContextWindow } from '@/features/ai/catalog'
import {
  branchDraftFromThread,
  branchDraftsEqual,
  projectPendingTarget,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import { branchTarget } from '@/features/ai/chat/session-entry-tree'
import { toThreadSelectionItem } from '@/features/ai/chat/thread-selection'
import { useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'
import { isConflictError, isNotFoundError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { translate, useI18n } from '@/shared/i18n'

/** 409 = stale revision or non-quiescent Thread; never swallow it silently. */
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
  paneId,
  threadId,
  focused,
  threadSort,
  onFocus,
  onThreadChange,
  onThreadSortChange,
  initialDraft,
  initialReplay,
  onReplayInitialized,
}: {
  chatId: string
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  paneId: string
  threadId: string
  focused: boolean
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  /** Restores the composer text after a failed first send; independent of replay identity. */
  initialDraft?: string
  initialReplay?: CommandBatchReplay
  onReplayInitialized?: () => void
}) {
  const { t } = useI18n()
  const environmentNames = useMemo(
    () => new Map(environments.map((environment) => [environment.id, environment.name])),
    [environments],
  )
  // buildBatch depends on the controller's snapshot thread; the controller is created below, so
  // the stable callback delegates through a ref that is assigned on every render before use.
  const buildBatchRef = useRef<((content: string) => CommandBatchPlan | null) | null>(null)
  const controller = useAgentThreadController(
    threadId,
    initialDraft ?? initialReplay?.content ?? '',
    initialReplay,
    (content) => buildBatchRef.current?.(content) ?? null,
    environmentNames,
  )
  // Pane-local branch draft: initialized from the durable Thread snapshot, edited pane-locally,
  // applied atomically with the next message batch. base follows the snapshot; queued SET_*
  // commands project the effective base so consecutive sends never resend in-flight settings.
  const [branchState, setBranchState] = useState<{
    base: BranchDraft
    draft: BranchDraft
    initialized: boolean
  } | null>(null)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [environmentModalOpen, setEnvironmentModalOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [rebindBlockedReason, setRebindBlockedReason] = useState<string | null>(null)
  const queryClient = useQueryClient()
  const boundThreadIdRef = useRef<string | null>(null)

  // Rebinding the pane to another Thread clears all pane-local state and re-initializes the
  // draft from the new snapshot (the controller also resets its stop/decision replay state).
  useEffect(() => {
    if (boundThreadIdRef.current === threadId) {
      return
    }
    boundThreadIdRef.current = threadId
    setBranchState(null)
    setHistoryOpen(false)
    setAgentModalOpen(false)
    setEnvironmentModalOpen(false)
    setThreadModalOpen(false)
    setRebindBlockedReason(null)
  }, [threadId])

  const effectiveBase = useMemo(() => {
    if (branchState == null) {
      return null
    }
    return projectPendingTarget(branchState.base, controller.queuedCommands)
  }, [branchState, controller.queuedCommands])
  const dirty =
    branchState != null
    && effectiveBase != null
    && !branchDraftsEqual(effectiveBase, branchState.draft)
  const hasPendingCommands = controller.queuedCommands.length > 0

  useEffect(() => {
    // A failed first-send recovery is consumed once: either form (text-only 409 recovery or
    // text + exact-batch replay) initializes in the controller, then the pane state clears so
    // switching away and back never re-applies a stale recovery.
    if (initialReplay || initialDraft) {
      onReplayInitialized?.()
    }
  }, [initialDraft, initialReplay, onReplayInitialized])

  useEffect(() => {
    if (isNotFoundError(controller.messagesError)) {
      onThreadChange(null)
    }
  }, [controller.messagesError, onThreadChange])

  // Rebind to a fresh Thread initializes the draft from its snapshot (base === draft, clean).
  useEffect(() => {
    const thread = controller.thread
    if (!thread) {
      return
    }
    setBranchState((current) => {
      const snapshotDraft = branchDraftFromThread(thread)
      if (current == null) {
        return { base: snapshotDraft, draft: snapshotDraft, initialized: true }
      }
      if (!current.initialized) {
        return { ...current, base: snapshotDraft, draft: snapshotDraft, initialized: true }
      }
      // Durable base follows the snapshot; the user draft is never silently overwritten.
      return { ...current, base: snapshotDraft }
    })
  }, [controller.thread])

  const buildBatch = useCallback(
    (content: string): CommandBatchPlan | null => {
      const thread = controller.thread
      if (!thread || branchState == null || effectiveBase == null) {
        return null
      }
      return buildMessageBatchPlan({
        thread,
        effectiveBase,
        draft: branchState.draft,
        content,
      })
    },
    [branchState, controller.thread, effectiveBase],
  )
  useEffect(() => {
    // Ref is consumed by controller submit handlers (event-driven, always after effects).
    buildBatchRef.current = buildBatch
  })

  const rebindMutation = useMutation({
    mutationFn: (entry: HarnessSessionEntryDTO) => {
      const target = branchTarget(entry)
      if (!target.headEntryId) {
        return Promise.reject(new Error(t('ai.runtime.action.rootNotBranchable')))
      }
      const thread = controller.thread
      if (!thread) {
        return Promise.reject(new Error(t('ai.runtime.action.threadNotLoaded')))
      }
      return harnessService.updateThreadHead(threadId, {
        targetEntryId: target.headEntryId,
        expectedRevision: thread.revision,
      })
    },
    onSuccess: async (updatedThread: HarnessThreadDTO, entry: HarnessSessionEntryDTO) => {
      setHistoryOpen(false)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
      // Successful relocation re-initializes the branch draft from the target Thread (yolo
      // stays server value; branch settings come from the returned Thread).
      const snapshotDraft = branchDraftFromThread(updatedThread)
      setBranchState({ base: snapshotDraft, draft: snapshotDraft, initialized: true })
      // USER/CUSTOM rewinds restore their editable source text into the composer.
      controller.setDraft(branchTarget(entry).draft)
    },
  })

  // Pane transition gates:
  // - paneDirty = branch draft dirty OR non-empty composer text (accepted message already
  //   cleared the composer; a non-empty composer is new unsent input).
  // - panePending = any in-flight mutation that makes a switch unsafe: queued commands,
  //   command HTTP, head relocation, stop, approval, an undecided exact batch replay, or an
  //   ambiguous Stop operation awaiting its exact retry.
  const paneDirty = dirty || controller.draft.trim() !== ''
  const panePending =
    hasPendingCommands
    || controller.pending
    || rebindMutation.isPending
    || controller.stopPending
    || controller.stopReplayPending
    || controller.approvalPending
    || controller.replayPending

  const threadPicker = useChatThreadPicker(chatId, threadModalOpen, threadSort)
  const threadItems = threadPicker.items.map((item) => toThreadSelectionItem(item, threadSort))

  function editDraft(patch: Partial<BranchDraft>) {
    setBranchState((current) => {
      if (current == null) {
        return current
      }
      return { ...current, draft: { ...current.draft, ...patch } }
    })
  }

  function selectAgent(selectedAgentName: string) {
    setRebindBlockedReason(null)
    const agent = agents.find((item) => item.name === selectedAgentName)
    if (!agent) {
      setRebindBlockedReason(t('ai.runtime.action.agentUnresolvable', { agent: selectedAgentName }))
      return
    }
    // Draft-local edit: adopt the new agent name + its active tool set; the frozen
    // model/thinking/environment/yolo selection is preserved.
    editDraft({ agentName: selectedAgentName, activeTools: [...agent.config.tools] })
    setAgentModalOpen(false)
  }

  function selectEnvironment(environmentId: string | null) {
    setRebindBlockedReason(null)
    editDraft({ environmentId })
    setEnvironmentModalOpen(false)
  }

  function toggleYolo() {
    setRebindBlockedReason(null)
    setBranchState((current) => {
      if (current == null) {
        return current
      }
      return {
        ...current,
        draft: { ...current.draft, yoloEnabled: !current.draft.yoloEnabled },
      }
    })
  }

  function selectThread(selectedThreadId: string) {
    setThreadModalOpen(false)
    if (selectedThreadId === threadId) {
      // Selecting the currently bound Thread needs no confirmation and changes nothing.
      return
    }
    if (panePending) {
      setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
      return
    }
    if (paneDirty && !window.confirm(t('ai.chat.history.confirmDiscardDraft'))) {
      return
    }
    onThreadChange(selectedThreadId)
  }

  /** Only logically quiescent Threads accept a head rebind (mirrors the server classifier). */
  function isRelocatable(): boolean {
    const thread = controller.thread
    return thread != null
      && (thread.status === 'IDLE' || thread.status === 'CONTINUATION_DUE')
      && !panePending
  }

  /** /tree relocation: non-relocatable status or pending commands block it up front. */
  function openHistory() {
    if (!isRelocatable()) {
      setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
      return
    }
    setRebindBlockedReason(null)
    setHistoryOpen(true)
  }

  function rebindTo(entry: HarnessSessionEntryDTO) {
    if (panePending) {
      setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
      return
    }
    if (paneDirty && !window.confirm(t('ai.chat.history.confirmDiscardDraft'))) {
      return
    }
    rebindMutation.mutate(entry)
  }

  function handleCommand(command: ThreadCommand) {
    onFocus()
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'session':
        // Global Session rebind no longer exists: stays visible but disabled.
        return
      case 'thread':
        if (panePending) {
          setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
          return
        }
        setThreadModalOpen(true)
        return
      case 'agent':
        setAgentModalOpen(true)
        return
      case 'environment':
        setEnvironmentModalOpen(true)
        return
      case 'yolo':
        toggleYolo()
        return
      case 'tree':
        openHistory()
        return
      case 'new':
        if (panePending) {
          setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
          return
        }
        if (paneDirty && !window.confirm(t('ai.chat.history.confirmDiscardDraft'))) {
          return
        }
        controller.setDraft('')
        onThreadChange(null)
        return
      default:
        controller.runCommand(command)
    }
  }

  const draft = branchState?.draft
  const draftModel = draft
    ? controller.models.find(
        (item) =>
          item.providerName === draft.model.providerName
          && item.name === draft.model.modelName,
      )
    : undefined
  const labels: ChatPanelLabels = {
    // Footer always reflects the pane-local draft (agent/model/variant/environment), falling
    // back to the durable snapshot labels only while the draft is still initializing.
    agentName: draft?.agentName || controller.runtimeLabels.agentName,
    providerName: draft?.model.providerName || controller.runtimeLabels.providerName,
    modelName: draft?.model.providerName && draft.model.modelName
      ? `${draft.model.providerName}/${draft.model.modelName}`
      : controller.runtimeLabels.modelName,
    variantName: draft?.model.variant || controller.runtimeLabels.variantName,
    environmentDisplayName:
      draft?.environmentId != null
        ? (environmentNames.get(draft.environmentId) ?? draft.environmentId)
        : (draft?.environmentId == null && draft != null
            ? null
            : controller.runtimeLabels.environmentDisplayName),
    contextWindow: extractContextWindow(draftModel) ?? controller.runtimeLabels.contextWindow,
  }
  const transcript: ChatPanelTranscriptInput = {
    timeline: controller.timeline,
    bodyRef: controller.bodyRef,
    loading: controller.messagesLoading,
    error: controller.messagesError,
    approvalPending: controller.approvalPending,
    onDecideApproval: (_message, decision) => {
      const invocationId = _message.invocationId
      if (invocationId) {
        void controller.decideApproval(invocationId, decision)
      }
    },
  }
  const composer: ChatPanelComposerInput = {
    draft: controller.draft,
    // Pending covers the in-flight HTTP request (also disables send: canSend checks disabled),
    // plus the branch draft and buildBatch readiness so one replayRef never serves concurrent
    // CAS requests.
    pending: controller.pending || rebindMutation.isPending,
    disabled:
      controller.pending
      || controller.disabled
      || rebindMutation.isPending
      || branchState == null
      || effectiveBase == null,
    onDraftChange: controller.setDraft,
    onSubmit: () => {
      void controller.submitMessage()
    },
    onCommand: handleCommand,
    commands: BOUND_PANE_COMMANDS,
  }
  const footer: ChatPanelFooterInput = {
    yoloEnabled: branchState?.draft.yoloEnabled ?? controller.thread?.yoloEnabled,
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
      {historyOpen ? (
        <HistoryBranchPanel
          entries={controller.entries}
          currentHeadEntryId={controller.thread?.headEntryId}
          loading={controller.messagesLoading}
          queryError={controller.messagesError}
          pending={rebindMutation.isPending}
          rebindError={rebindMutation.error ? rebindErrorMessage(rebindMutation.error) : null}
          onClose={() => {
            setHistoryOpen(false)
            rebindMutation.reset()
          }}
          onRebind={rebindTo}
        />
      ) : null}
      {/* /thread switches which Chat-scoped Thread this pane shows; no Thread is mutated. */}
      <SelectionListModal
        open={threadModalOpen}
        title={t('ai.chat.selectThread')}
        items={threadItems}
        sort={threadSort}
        onSortChange={onThreadSortChange}
        loading={threadPicker.isLoading}
        emptyText={t('ai.chat.noThreads')}
        onClose={() => setThreadModalOpen(false)}
        onSelect={selectThread}
      />
      <AgentSelectionModal
        open={agentModalOpen}
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
        selectedEnvironmentId={branchState?.draft.environmentId ?? null}
        onClose={() => setEnvironmentModalOpen(false)}
        onSelect={(environmentId) => {
          selectEnvironment(environmentId)
        }}
      />
    </section>
  )
}
