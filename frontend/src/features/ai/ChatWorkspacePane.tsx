import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChatPanel } from '@/features/ai/ChatPanel'
import { HistoryBranchPanel } from '@/features/ai/HistoryBranchPanel'
import { AgentSelectionModal, SelectionListModal } from '@/features/ai/SelectionListModal'
import {
  isPaneBound,
  sortWithRunningFirst,
  type ChatPane,
  type PaneSortPreference,
} from '@/features/ai/chat-pane-state'
import {
  canRebindThread,
  isRunningThread,
  toThreadSelectionItem,
} from '@/features/ai/chat-session-picker'
import { performBlankPaneFirstSend } from '@/features/ai/chat-first-send'
import { branchTarget } from '@/features/ai/session-entry-tree'
import {
  threadCommandsForScene,
  type ThreadCommand,
} from '@/features/ai/thread-panel/thread-commands'
import { ThreadComposer } from '@/features/ai/thread-panel/ThreadComposer'
import { ThreadStatusFooter } from '@/features/ai/thread-panel/ThreadStatusFooter'
import { useAgentThreadController } from '@/features/ai/useAgentThreadController'
import { useChatSessionPicker } from '@/features/ai/useChatSessionPicker'
import { modelRef, toAgentModelViews, type AgentModelView } from '@/features/ai/AgentModelView'
import type {
  AgentDefinitionDTO,
  ChatDTO,
  HarnessSessionEntryDTO,
} from '@/shared/api/contracts'
import { extractContextWindow, extractDefaultVariantFromModel } from '@/features/ai/ai-model-draft-codec'
import { variantOptionsFromModel } from '@/features/ai/ai-draft-variant-options'
import { agentService } from '@/shared/api/agent-service'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

/** Blank pane: full stable command table; unsupported entries are disabled (grayed). */
export const BLANK_PANE_COMMANDS: ThreadCommand[] = threadCommandsForScene('blank')
export const BOUND_PANE_COMMANDS: ThreadCommand[] = threadCommandsForScene('bound')

function resolveDefaultAgent(
  chat: ChatDTO | undefined,
  agents: AgentDefinitionDTO[],
): AgentDefinitionDTO | undefined {
  if (!chat?.defaultAgentId) {
    return undefined
  }
  return agents.find((agent) => String(agent.id) === String(chat.defaultAgentId))
}

/** 空白 pane 尚无 Thread 时，用 Chat 默认 Agent 的 model/variant 填 footer（与已绑定 pane 一致） */
function resolveBlankPaneFooterLabels(
  agent: AgentDefinitionDTO | undefined,
  models: AgentModelView[],
) {
  if (!agent) {
    return {
      agentName: '（无 Agent）',
      providerName: undefined as string | undefined,
      modelName: undefined as string | undefined,
      variantName: undefined as string | undefined,
      contextWindow: undefined as number | undefined,
    }
  }
  const modelId = agent.modelId ? String(agent.modelId) : ''
  const model = models.find((item) => String(item.id) === modelId)
  return {
    agentName: agent.name || '（无 Agent）',
    providerName: model?.providerName || undefined,
    modelName: model ? modelRef(model) : modelId || undefined,
    variantName: agent.variant || undefined,
    contextWindow: extractContextWindow(model),
  }
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  if (typeof error === 'string' && error.trim()) {
    return error
  }
  return fallback
}

/** 409 = stale executionEpoch or non-quiescent Thread; never swallow it silently. */
function rebindErrorMessage(error: unknown): string {
  if (isConflictError(error)) {
    return `无法重定位 Thread：状态已变化（${errorMessage(error, '冲突')}），请刷新后重试`
  }
  return errorMessage(error, '重定位 Thread 失败')
}

export function ChatWorkspacePane({
  chat,
  agents,
  pane,
  focused,
  sessionSort,
  threadSort,
  onFocus,
  onThreadChange,
  onSessionSortChange,
  onThreadSortChange,
  onDefaultAgentChange,
}: {
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  pane: ChatPane
  focused: boolean
  sessionSort: PaneSortPreference
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onSessionSortChange: (sort: PaneSortPreference) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  onDefaultAgentChange: (agentId: string) => Promise<void>
}) {
  if (isPaneBound(pane.threadId)) {
    return (
      <BoundThreadPane
        agents={agents}
        paneId={pane.id}
        threadId={pane.threadId}
        focused={focused}
        sessionSort={sessionSort}
        threadSort={threadSort}
        onFocus={onFocus}
        onThreadChange={onThreadChange}
        onSessionSortChange={onSessionSortChange}
        onThreadSortChange={onThreadSortChange}
      />
    )
  }

  return (
    <BlankComposerPane
      chat={chat}
      agents={agents}
      focused={focused}
      threadSort={threadSort}
      onFocus={onFocus}
      onThreadChange={onThreadChange}
      onThreadSortChange={onThreadSortChange}
      onDefaultAgentChange={onDefaultAgentChange}
    />
  )
}

function BlankComposerPane({
  chat,
  agents,
  focused,
  threadSort,
  onFocus,
  onThreadChange,
  onThreadSortChange,
  onDefaultAgentChange,
}: {
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  focused: boolean
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  onDefaultAgentChange: (agentId: string) => Promise<void>
}) {
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [pendingContent, setPendingContent] = useState<string | null>(null)
  const queryClient = useQueryClient()
  const threadsQuery = useQuery({
    queryKey: queryKeys.threads.list,
    queryFn: () => harnessService.listThreads(),
    enabled: threadModalOpen,
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const providersQuery = useQuery({
    queryKey: queryKeys.providers.list,
    queryFn: () => agentService.listProviders(),
  })
  const providers = providersQuery.data?.results ?? []
  const models: AgentModelView[] = toAgentModelViews(
    modelsQuery.data?.results ?? [],
    providers,
  )
  const defaultAgent = resolveDefaultAgent(chat, agents)
  const footerLabels = resolveBlankPaneFooterLabels(defaultAgent, models)
  const agentLabel = defaultAgent?.name || (chat?.defaultAgentId ? '（Agent 已删除/缺失）' : '（无 Agent）')

  async function runFirstSend(agentId: string, content: string) {
    setPending(true)
    setActionError(null)
    try {
      const result = await performBlankPaneFirstSend({
        agentDefinitionId: agentId,
        content,
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.list }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.list }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(result.sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(result.thread.threadId) }),
      ])
      setDraft('')
      setPendingContent(null)
      onThreadChange(result.thread.threadId)
    } catch (error) {
      setActionError(errorMessage(error, '首发失败'))
      setDraft(content)
    } finally {
      setPending(false)
    }
  }

  async function handleSubmit() {
    const content = draft.trim()
    if (!content || content.startsWith('/') || pending) {
      return
    }
    onFocus()
    if (!defaultAgent) {
      setPendingContent(content)
      setAgentModalOpen(true)
      return
    }
    await runFirstSend(String(defaultAgent.id), content)
  }

  function handleCommand(command: ThreadCommand) {
    onFocus()
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'thread':
        setThreadModalOpen(true)
        return
      case 'agent':
        setAgentModalOpen(true)
        return
      default:
        setActionError(command.disabledReason || `当前场景不可用：/${command.id}`)
    }
  }

  async function handleAgentSelected(agentId: string) {
    setAgentModalOpen(false)
    const content = pendingContent?.trim()
    setActionError(null)
    try {
      await onDefaultAgentChange(agentId)
      if (content) {
        await runFirstSend(agentId, content)
      }
    } catch (error) {
      setActionError(errorMessage(error, '更新默认 Agent 失败'))
      if (content) {
        setDraft(content)
      }
    }
  }

  return (
    <section className={`chat-pane ${focused ? 'focused' : ''}`} onMouseDown={onFocus}>
      {/* 与有 Thread 时同一套 shell / composer 结构，避免 blank 与 thread 输入框样式分叉 */}
      <section className="chat-shell thread-panel blank-pane">
        <main className="chat-main thread-panel-main">
          <div className="blank-pane-body">
            <h2>新对话</h2>
            <p>输入后创建并 bootstrap 新 Thread；/agent 选默认 Agent，/thread 复用已有 Thread。</p>
            {actionError ? <div className="thread-error-panel">{actionError}</div> : null}
          </div>
          <ThreadComposer
            draft={draft}
            pending={pending}
            disabled={pending}
            onDraftChange={setDraft}
            onSubmit={() => {
              void handleSubmit()
            }}
            onCommand={handleCommand}
            commands={BLANK_PANE_COMMANDS}
          />
          <ThreadStatusFooter
            agentName={defaultAgent ? footerLabels.agentName : agentLabel}
            providerName={defaultAgent ? footerLabels.providerName : undefined}
            modelName={defaultAgent ? footerLabels.modelName : undefined}
            variantName={defaultAgent ? footerLabels.variantName : undefined}
            contextWindow={defaultAgent ? footerLabels.contextWindow : undefined}
            yoloEnabled={false}
            onAgentClick={() => {
              onFocus()
              setAgentModalOpen(true)
            }}
          />
        </main>
      </section>
      <AgentSelectionModal
        open={agentModalOpen}
        agents={agents.map((agent) => ({
          id: String(agent.id),
          name: agent.name,
          description: agent.description,
        }))}
        onClose={() => {
          setAgentModalOpen(false)
          setPendingContent(null)
        }}
        onSelect={(agentId) => {
          void handleAgentSelected(agentId)
        }}
      />
      {/* /thread only rebinds the pane; no Thread is mutated. */}
      <SelectionListModal
        open={threadModalOpen}
        title="选择 Thread"
        items={sortWithRunningFirst(threadsQuery.data ?? [], threadSort, isRunningThread).map(
          toThreadSelectionItem,
        )}
        sort={threadSort}
        onSortChange={onThreadSortChange}
        emptyText="暂无 Thread"
        onClose={() => setThreadModalOpen(false)}
        onSelect={(selectedThreadId) => {
          setThreadModalOpen(false)
          onThreadChange(selectedThreadId)
        }}
      />
    </section>
  )
}

function BoundThreadPane({
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
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.entries(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) }),
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

  return (
    <section className={`chat-pane ${focused ? 'focused' : ''}`} onMouseDown={onFocus} data-pane-id={paneId}>
      <ChatPanel
        timeline={controller.timeline}
        runtimeLabels={controller.runtimeLabels}
        working={controller.working}
        messagesLoading={controller.messagesLoading}
        messagesError={controller.messagesError}
        bodyRef={controller.bodyRef}
        draft={controller.draft}
        pending={controller.pending}
        disabled={controller.disabled}
        observability={controller.observability}
        actionError={rebindBlockedReason ?? controller.actionError}
        onDismissActionError={() => {
          setRebindBlockedReason(null)
          controller.dismissActionError()
        }}
        onDraftChange={controller.setDraft}
        onSubmit={() => {
          void controller.submitMessage()
        }}
        onCommand={handleCommand}
        commands={BOUND_PANE_COMMANDS}
        onAgentClick={() => {
          onFocus()
          setAgentModalOpen(true)
        }}
        onModelClick={() => {
          onFocus()
          setModelModalOpen(true)
        }}
        onVariantClick={() => {
          onFocus()
          setVariantModalOpen(true)
        }}
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
            await queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) })
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
