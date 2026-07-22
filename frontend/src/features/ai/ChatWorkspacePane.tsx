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
import { isRunningThread } from '@/features/ai/chat-session-picker'
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
  HarnessThreadDTO,
} from '@/shared/api/contracts'
import { extractContextWindow, extractDefaultVariantFromModel } from '@/features/ai/ai-model-draft-codec'
import { variantOptionsFromModel } from '@/features/ai/ai-draft-variant-options'
import { agentService } from '@/shared/api/agent-service'
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

export function ChatWorkspacePane({
  chatId,
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
  chatId: string
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
        chatId={chatId}
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
      chatId={chatId}
      chat={chat}
      agents={agents}
      focused={focused}
      sessionSort={sessionSort}
      onFocus={onFocus}
      onThreadChange={onThreadChange}
      onSessionSortChange={onSessionSortChange}
      onDefaultAgentChange={onDefaultAgentChange}
    />
  )
}

function BlankComposerPane({
  chatId,
  chat,
  agents,
  focused,
  sessionSort,
  onFocus,
  onThreadChange,
  onSessionSortChange,
  onDefaultAgentChange,
}: {
  chatId: string
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  focused: boolean
  sessionSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onSessionSortChange: (sort: PaneSortPreference) => void
  onDefaultAgentChange: (agentId: string) => Promise<void>
}) {
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [sessionModalOpen, setSessionModalOpen] = useState(false)
  const [pendingContent, setPendingContent] = useState<string | null>(null)
  const queryClient = useQueryClient()
  const sessionPicker = useChatSessionPicker(chatId, sessionModalOpen, sessionSort)
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
        chatId,
        agentDefinitionId: agentId,
        content,
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.sessions(chatId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(result.sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(result.threadId) }),
      ])
      setDraft('')
      setPendingContent(null)
      onThreadChange(result.threadId)
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
      case 'session':
        setSessionModalOpen(true)
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
            <p>输入后创建新的 Session / Thread；/agent 选默认 Agent，/session 复用已有会话。</p>
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
      <SelectionListModal
        open={sessionModalOpen}
        title="选择 Session"
        items={sessionPicker.sessionItems}
        sort={sessionSort}
        onSortChange={onSessionSortChange}
        emptyText="当前 Chat 暂无 Session"
        onClose={() => setSessionModalOpen(false)}
        onSelect={(selectedSessionId) => {
          const session = sessionPicker.findSession(selectedSessionId)
          if (!session) {
            return
          }
          setSessionModalOpen(false)
          onThreadChange(session.mainThreadId)
        }}
      />
    </section>
  )
}

function BoundThreadPane({
  chatId,
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
  chatId: string
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
  // sessionId is not persisted on pane; resolve from Thread after load.
  const controller = useAgentThreadController(threadId)
  const sessionId = controller.thread?.sessionId ?? ''
  const queryClient = useQueryClient()
  const [historyBranchOpen, setHistoryBranchOpen] = useState(false)
  const [sessionModalOpen, setSessionModalOpen] = useState(false)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [modelModalOpen, setModelModalOpen] = useState(false)
  const [variantModalOpen, setVariantModalOpen] = useState(false)
  const [branchDraft, setBranchDraft] = useState('')
  const sessionPicker = useChatSessionPicker(chatId, sessionModalOpen, sessionSort)

  useEffect(() => {
    if (branchDraft) {
      controller.setDraft(branchDraft)
      setBranchDraft('')
    }
    // Only reapply explicit branch draft once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [branchDraft])

  const threadsQuery = useQuery({
    queryKey: queryKeys.sessions.threads(sessionId),
    queryFn: () => harnessService.listSessionThreads(sessionId),
    enabled: threadModalOpen || Boolean(sessionId),
  })
  const sessionEntriesQuery = useQuery({
    queryKey: queryKeys.sessions.entries(sessionId),
    queryFn: () => harnessService.listSessionEntries(sessionId),
    enabled: historyBranchOpen && Boolean(sessionId),
  })
  const branchMutation = useMutation({
    mutationFn: (entry: HarnessSessionEntryDTO) => {
      const target = branchTarget(entry)
      if (!target.fromEntryId) {
        return Promise.reject(new Error('根节点不能作为可编辑消息分支'))
      }
      if (!sessionId) {
        return Promise.reject(new Error('Thread 尚未加载 Session'))
      }
      return harnessService.createSessionThread(sessionId, { fromEntryId: target.fromEntryId })
    },
    onSuccess: async (nextThread, entry) => {
      setHistoryBranchOpen(false)
      if (sessionId) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.sessions.threads(sessionId) })
      }
      setBranchDraft(branchTarget(entry).draft)
      onThreadChange(nextThread.threadId)
    },
  })

  const threadItems = sortWithRunningFirst(
    threadsQuery.data ?? [],
    threadSort,
    isRunningThread,
  ).map((thread) => toThreadItem(thread, sessionId))

  function handleCommand(command: ThreadCommand) {
    onFocus()
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'session':
        setSessionModalOpen(true)
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
        setHistoryBranchOpen(true)
        return
      case 'new':
        // Detach pane from current Session/Thread so next send creates a fresh pair.
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
        retryPresentation={controller.retryPresentation}
        messagesLoading={controller.messagesLoading}
        messagesError={controller.messagesError}
        bodyRef={controller.bodyRef}
        draft={controller.draft}
        pending={controller.pending}
        disabled={controller.disabled}
        observability={controller.observability}
        taskTimeline={controller.taskTimeline}
        actionError={controller.actionError}
        onDismissActionError={controller.dismissActionError}
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
      {historyBranchOpen ? (
        <HistoryBranchPanel
          entries={sessionEntriesQuery.data ?? []}
          currentHeadEntryId={controller.thread?.headEntryId}
          loading={sessionEntriesQuery.isLoading}
          queryError={sessionEntriesQuery.error}
          pending={branchMutation.isPending}
          creationError={branchMutation.error}
          onClose={() => {
            setHistoryBranchOpen(false)
            branchMutation.reset()
          }}
          onCreate={(entry) => branchMutation.mutate(entry)}
        />
      ) : null}
      <SelectionListModal
        open={sessionModalOpen}
        title="选择 Session"
        items={sessionPicker.sessionItems}
        sort={sessionSort}
        onSortChange={onSessionSortChange}
        emptyText="当前 Chat 暂无 Session"
        onClose={() => setSessionModalOpen(false)}
        onSelect={(selectedSessionId) => {
          const session = sessionPicker.findSession(selectedSessionId)
          if (!session) {
            return
          }
          setSessionModalOpen(false)
          onThreadChange(session.mainThreadId)
        }}
      />
      <SelectionListModal
        open={threadModalOpen}
        title="选择 Thread"
        items={threadItems}
        sort={threadSort}
        onSortChange={onThreadSortChange}
        emptyText="当前 Session 暂无 Thread"
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

function toThreadItem(thread: HarnessThreadDTO, mainSessionId: string) {
  const running = isRunningThread(thread)
  return {
    id: thread.threadId,
    title: thread.threadId,
    subtitle: thread.activeAgentName || thread.sessionTitle || mainSessionId,
    badge: running ? 'RUNNING' : thread.status,
  }
}
