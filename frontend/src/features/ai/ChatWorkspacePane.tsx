import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChatPanel } from '@/features/ai/ChatPanel'
import { HistoryBranchPanel } from '@/features/ai/HistoryBranchPanel'
import { AgentSelectionModal, SelectionListModal } from '@/features/ai/SelectionListModal'
import {
  isPaneBound,
  sortWithRunningFirst,
  type ChatPane,
  type PaneSortPreference,
  type PaneTarget,
} from '@/features/ai/chat-pane-state'
import { isRunningThread } from '@/features/ai/chat-session-picker'
import { performBlankPaneFirstSend } from '@/features/ai/chat-first-send'
import { branchTarget } from '@/features/ai/session-entry-tree'
import { THREAD_COMMANDS, type ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import { ThreadComposer } from '@/features/ai/thread-panel/ThreadComposer'
import { ThreadStatusFooter } from '@/features/ai/thread-panel/ThreadStatusFooter'
import { useAgentThreadController } from '@/features/ai/useAgentThreadController'
import { useChatSessionPicker } from '@/features/ai/useChatSessionPicker'
import type {
  AgentDefinitionDTO,
  AgentModelWithProviderDTO,
  AgentProviderDTO,
  ChatDTO,
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts'
import { extractContextWindow } from '@/features/ai/ai-model-draft-codec'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

/** Blank panes only expose explicit Session reuse; first-send stays on plain submit. */
export const BLANK_PANE_COMMANDS: ThreadCommand[] = THREAD_COMMANDS.filter((command) => command.id === 'session')

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
  models: AgentModelWithProviderDTO[],
  providers: AgentProviderDTO[],
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
  const model =
    models.find((item) => String(item.id) === modelId)
    || models.find((item) => item.name === modelId)
  const provider = model
    ? providers.find((item) => String(item.id) === String(model.providerId))
    : undefined
  return {
    agentName: agent.name || '（无 Agent）',
    providerName: provider?.name || model?.providerName || undefined,
    modelName: model?.name || modelId || undefined,
    variantName: agent.variant || 'default',
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
  onTargetChange,
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
  onTargetChange: (target: PaneTarget) => void
  onSessionSortChange: (sort: PaneSortPreference) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  onDefaultAgentChange: (agentId: string) => Promise<void>
}) {
  if (isPaneBound(pane.target)) {
    return (
      <BoundThreadPane
        chatId={chatId}
        agents={agents}
        paneId={pane.id}
        sessionId={pane.target.sessionId}
        threadId={pane.target.threadId}
        focused={focused}
        sessionSort={sessionSort}
        threadSort={threadSort}
        onFocus={onFocus}
        onTargetChange={onTargetChange}
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
      onTargetChange={onTargetChange}
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
  onTargetChange,
  onSessionSortChange,
  onDefaultAgentChange,
}: {
  chatId: string
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  focused: boolean
  sessionSort: PaneSortPreference
  onFocus: () => void
  onTargetChange: (target: PaneTarget) => void
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
  const rawModels = modelsQuery.data?.results ?? []
  const providers = providersQuery.data?.results ?? []
  const models: AgentModelWithProviderDTO[] = rawModels.map((model) => {
    const provider = providers.find((item) => String(item.id) === String(model.providerId))
    return { ...model, providerName: provider?.name ?? null }
  })
  const defaultAgent = resolveDefaultAgent(chat, agents)
  const footerLabels = useMemo(
    () => resolveBlankPaneFooterLabels(defaultAgent, models, providers),
    [defaultAgent, models, providers],
  )
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
      onTargetChange({ sessionId: result.sessionId, threadId: result.threadId })
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
    if (command.id === 'session') {
      setSessionModalOpen(true)
      return
    }
    setActionError(`未知命令：${command.id}`)
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
            <p>输入后创建 Session；也可用 /session 复用。</p>
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
          onTargetChange({ sessionId: session.sessionId, threadId: session.mainThreadId })
        }}
      />
    </section>
  )
}

function BoundThreadPane({
  chatId,
  agents,
  paneId,
  sessionId,
  threadId,
  focused,
  sessionSort,
  threadSort,
  onFocus,
  onTargetChange,
  onSessionSortChange,
  onThreadSortChange,
}: {
  chatId: string
  agents: AgentDefinitionDTO[]
  paneId: string
  sessionId: string
  threadId: string
  focused: boolean
  sessionSort: PaneSortPreference
  threadSort: PaneSortPreference
  onFocus: () => void
  onTargetChange: (target: PaneTarget) => void
  onSessionSortChange: (sort: PaneSortPreference) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
}) {
  const controller = useAgentThreadController(threadId, sessionId)
  const queryClient = useQueryClient()
  const [historyBranchOpen, setHistoryBranchOpen] = useState(false)
  const [sessionModalOpen, setSessionModalOpen] = useState(false)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
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
      return harnessService.createSessionThread(sessionId, { fromEntryId: target.fromEntryId })
    },
    onSuccess: async (nextThread, entry) => {
      setHistoryBranchOpen(false)
      await queryClient.invalidateQueries({ queryKey: queryKeys.sessions.threads(sessionId) })
      setBranchDraft(branchTarget(entry).draft)
      onTargetChange({ sessionId, threadId: nextThread.threadId })
    },
  })

  const threadItems = useMemo(() => {
    const threads = sortWithRunningFirst(threadsQuery.data ?? [], threadSort, isRunningThread)
    return threads.map((thread) => toThreadItem(thread, sessionId))
  }, [sessionId, threadSort, threadsQuery.data])

  function handleCommand(command: ThreadCommand) {
    onFocus()
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
      case 'tree':
        setHistoryBranchOpen(true)
        return
      default:
        controller.runCommand(command)
    }
  }

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
        taskTimeline={controller.taskTimeline}
        actionError={controller.actionError}
        onDismissActionError={controller.dismissActionError}
        onDraftChange={controller.setDraft}
        onSubmit={() => {
          void controller.submitMessage()
        }}
        onCommand={handleCommand}
        commands={THREAD_COMMANDS}
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
          onTargetChange({ sessionId: session.sessionId, threadId: session.mainThreadId })
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
          onTargetChange({ sessionId, threadId: selectedThreadId })
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
    </section>
  )
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
