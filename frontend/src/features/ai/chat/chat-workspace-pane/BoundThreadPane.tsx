import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ChatPanel,
  type ChatPanelActivityInput,
  type ChatPanelComposerInput,
  type ChatPanelFooterInput,
  type ChatPanelLabels,
  type ChatPanelTranscriptInput,
  type ThreadCommand,
  useAgentThreadController,
} from '@/features/ai/runtime'
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import { AgentSelectionModal, SelectionListModal } from '@/features/ai/chat/SelectionListModal'
import { sortWithRunningFirst, type PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import {
  canRebindThread,
  isRunningThread,
  toThreadSelectionItem,
} from '@/features/ai/chat/chat-session-picker'
import { branchTarget } from '@/features/ai/chat/session-entry-tree'
import { BOUND_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { errorMessage } from '@/features/ai/chat/chat-workspace-pane/pane-errors'
import { toThreadUsageSummary } from '@/features/ai/chat/chat-workspace-pane/usage-adapter'
import { useChatSessionPicker } from '@/features/ai/chat/useChatSessionPicker'
import {
  extractDefaultVariantFromModel,
  modelRef,
  variantOptionsFromModel,
} from '@/features/ai/catalog'
import type { AgentDefinitionDTO, HarnessSessionEntryDTO } from '@/shared/api/contracts'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

/** 409 = stale executionEpoch or non-quiescent Thread; never swallow it silently. */
function rebindErrorMessage(error: unknown): string {
  if (isConflictError(error)) {
    return `无法重定位 Thread：状态已变化（${errorMessage(error, '冲突')}），请刷新后重试`
  }
  return errorMessage(error, '重定位 Thread 失败')
}

function firstNonEmpty(...values: unknown[]): string {
  for (const value of values) {
    if (value == null) {
      continue
    }
    const text = String(value).trim()
    if (text) {
      return text
    }
  }
  return ''
}

export function BoundThreadPane({
  agents,
  paneId,
  threadId,
  focused,
  sessionSort,
  threadSort,
  onFocus,
  onThreadChange,
  onSessionSortChange,
  onThreadSortChange,
}: {
  agents: AgentDefinitionDTO[]
  paneId: string
  threadId: string
  focused: boolean
  sessionSort: PaneSortPreference
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onSessionSortChange: (sort: PaneSortPreference) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
}) {
  // sessionId is not persisted on pane; resolve from the Thread head after load.
  const controller = useAgentThreadController(threadId)
  const sessionId = controller.sessionId
  const queryClient = useQueryClient()
  // Session whose Entry Tree the history panel is browsing: current Session for /tree, the
  // picked Session for /session. Both end in the same PUT /head on the current Thread.
  const [historySessionId, setHistorySessionId] = useState<string | null>(null)
  const [sessionModalOpen, setSessionModalOpen] = useState(false)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [modelModalOpen, setModelModalOpen] = useState(false)
  const [variantModalOpen, setVariantModalOpen] = useState(false)
  const [branchDraft, setBranchDraft] = useState('')
  const [rebindBlockedReason, setRebindBlockedReason] = useState<string | null>(null)
  const sessionPicker = useChatSessionPicker(sessionModalOpen, sessionSort)
  const rebindable = canRebindThread(controller.thread)

  useEffect(() => {
    if (branchDraft) {
      controller.setDraft(branchDraft)
      setBranchDraft('')
    }
    // Only reapply explicit branch draft once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [branchDraft])

  const threadsQuery = useQuery({
    queryKey: queryKeys.threads.list,
    queryFn: () => harnessService.listThreads(),
    enabled: threadModalOpen,
  })
  const sessionEntriesQuery = useQuery({
    queryKey: queryKeys.sessions.entries(historySessionId ?? ''),
    queryFn: () => harnessService.listSessionEntries(historySessionId!),
    enabled: Boolean(historySessionId),
  })
  const rebindMutation = useMutation({
    mutationFn: (entry: HarnessSessionEntryDTO) => {
      const target = branchTarget(entry)
      if (!target.headEntryId) {
        return Promise.reject(new Error('根节点不能作为可编辑消息分支'))
      }
      if (!controller.thread) {
        return Promise.reject(new Error('Thread 尚未加载'))
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

  const threadItems = sortWithRunningFirst(
    threadsQuery.data ?? [],
    threadSort,
    isRunningThread,
  ).map(toThreadSelectionItem)

  /** /session and /tree both relocate the current Thread, so both need a quiescent Thread. */
  function openRebindTarget(open: () => void) {
    if (!rebindable) {
      setRebindBlockedReason('当前 Thread 正在运行，无法重定位；请先 /stop')
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
      case 'model':
        setModelModalOpen(true)
        return
      case 'variant':
        setVariantModalOpen(true)
        return
      case 'tree':
        openRebindTarget(() => setHistorySessionId(sessionId || null))
        return
      case 'new':
        // Detach the pane so the next send creates and bootstraps a fresh Thread.
        controller.setDraft('')
        onThreadChange(null)
        return
      default:
        controller.runCommand(command)
    }
  }

  const currentModelId = firstNonEmpty(
    controller.thread?.modelId,
    controller.agent?.modelId,
  )
  const currentModel =
    controller.models.find((model) => String(model.id) === currentModelId) ??
    controller.models.find((model) => model.name === currentModelId)
  const currentVariantOptions = variantOptionsFromModel(currentModel)

  const labels: ChatPanelLabels = {
    agentName: controller.runtimeLabels.agentName,
    providerName: controller.runtimeLabels.providerName,
    modelName: controller.runtimeLabels.modelName,
    variantName: controller.runtimeLabels.variantName,
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
    pending: controller.pending,
    disabled: controller.disabled,
    onDraftChange: controller.setDraft,
    onSubmit: () => {
      void controller.submitMessage()
    },
    onCommand: handleCommand,
    commands: BOUND_PANE_COMMANDS,
  }
  const footer: ChatPanelFooterInput = {
    yoloEnabled: controller.observability.yolo?.enabled,
    usage: controller.observability.usage
      ? toThreadUsageSummary(controller.observability.usage)
      : undefined,
    onAgentClick: () => {
      onFocus()
      setAgentModalOpen(true)
    },
    onModelClick: () => {
      onFocus()
      setModelModalOpen(true)
    },
    onVariantClick: () => {
      onFocus()
      setVariantModalOpen(true)
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
        title="选择 Session"
        items={sessionPicker.sessionItems}
        sort={sessionSort}
        onSortChange={onSessionSortChange}
        emptyText="暂无 Session"
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
        title="选择 Thread"
        items={threadItems}
        sort={threadSort}
        onSortChange={onThreadSortChange}
        emptyText="暂无 Thread"
        onClose={() => setThreadModalOpen(false)}
        onSelect={(selectedThreadId) => {
          setThreadModalOpen(false)
          onThreadChange(selectedThreadId)
        }}
      />
      <AgentSelectionModal
        open={agentModalOpen}
        agents={agents.map((agent) => ({
          id: String(agent.id),
          name: agent.name,
          description: agent.description,
        }))}
        onClose={() => setAgentModalOpen(false)}
        onSelect={(agentId) => {
          setAgentModalOpen(false)
          void controller.setThreadAgent(agentId).then(async () => {
            await queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
          })
        }}
      />
      <SelectionListModal
        open={modelModalOpen}
        title="选择 Model"
        items={controller.models.map((model) => ({
          id: String(model.id),
          title: modelRef(model),
          subtitle: model.description || undefined,
        }))}
        sort="recent"
        onSortChange={() => undefined}
        showSort={false}
        emptyText="暂无可用 Model"
        onClose={() => setModelModalOpen(false)}
        onSelect={(modelId) => {
          setModelModalOpen(false)
          const model = controller.models.find((item) => String(item.id) === modelId)
          if (!model) {
            return
          }
          const variant =
            extractDefaultVariantFromModel(model) ||
            variantOptionsFromModel(model)[0] ||
            'default'
          void controller.setThreadModel(String(model.id), variant)
        }}
      />
      <SelectionListModal
        open={variantModalOpen}
        title="选择 Variant"
        items={currentVariantOptions.map((variant) => ({
          id: variant,
          title: variant,
          subtitle: currentModel ? modelRef(currentModel) : undefined,
        }))}
        sort="recent"
        onSortChange={() => undefined}
        showSort={false}
        emptyText={currentModel ? '当前 Model 暂无 Variant' : '请先设置 Model'}
        onClose={() => setVariantModalOpen(false)}
        onSelect={(variant) => {
          setVariantModalOpen(false)
          if (!currentModel) {
            return
          }
          void controller.setThreadModel(String(currentModel.id), variant)
        }}
      />
    </section>
  )
}
