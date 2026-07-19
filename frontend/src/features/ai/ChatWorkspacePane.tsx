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
import { performBlankPaneFirstSend } from '@/features/ai/chat-first-send'
import { branchTarget } from '@/features/ai/session-entry-tree'
import { THREAD_COMMANDS, type ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import { ThreadComposer } from '@/features/ai/thread-panel/ThreadComposer'
import { ThreadStatusFooter } from '@/features/ai/thread-panel/ThreadStatusFooter'
import { useAgentThreadController } from '@/features/ai/useAgentThreadController'
import type {
  AgentDefinitionDTO,
  ChatDTO,
  HarnessSessionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

function resolveDefaultAgent(
  chat: ChatDTO | undefined,
  agents: AgentDefinitionDTO[],
): AgentDefinitionDTO | undefined {
  if (!chat?.defaultAgentId) {
    return undefined
  }
  return agents.find((agent) => String(agent.id) === String(chat.defaultAgentId))
}

function boundCommands(includeSession: boolean): ThreadCommand[] {
  if (includeSession) {
    return THREAD_COMMANDS
  }
  return THREAD_COMMANDS.filter((command) => command.id !== 'session' && command.id !== 'thread' && command.id !== 'agent')
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
      onFocus={onFocus}
      onTargetChange={onTargetChange}
      onDefaultAgentChange={onDefaultAgentChange}
    />
  )
}

function BlankComposerPane({
  chatId,
  chat,
  agents,
  focused,
  onFocus,
  onTargetChange,
  onDefaultAgentChange,
}: {
  chatId: string
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  focused: boolean
  onFocus: () => void
  onTargetChange: (target: PaneTarget) => void
  onDefaultAgentChange: (agentId: string) => Promise<void>
}) {
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [pendingContent, setPendingContent] = useState<string | null>(null)
  const queryClient = useQueryClient()
  const defaultAgent = resolveDefaultAgent(chat, agents)
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
      setActionError(error instanceof Error ? error.message : '首发失败')
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

  return (
    <section className={`chat-pane ${focused ? 'focused' : ''}`} onMouseDown={onFocus}>
      <div className="chat-pane-main blank-pane">
        <div className="blank-pane-body">
          <h2>新对话</h2>
          <p>输入消息后将创建独立 Session / Main Thread。</p>
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
          onCommand={() => undefined}
          commands={[]}
        />
        <ThreadStatusFooter agentName={agentLabel} yoloEnabled={false} />
      </div>
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
          setAgentModalOpen(false)
          const content = pendingContent?.trim()
          void onDefaultAgentChange(agentId).then(() => {
            if (content) {
              return runFirstSend(agentId, content)
            }
            return undefined
          })
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

  useEffect(() => {
    if (branchDraft) {
      controller.setDraft(branchDraft)
      setBranchDraft('')
    }
    // Only reapply explicit branch draft once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [branchDraft])

  const sessionsQuery = useQuery({
    queryKey: queryKeys.chats.sessions(chatId),
    queryFn: () => chatService.listChatSessions(chatId),
    enabled: sessionModalOpen,
  })
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

  const sessionItems = useMemo(() => {
    const sessions = sortWithRunningFirst(sessionsQuery.data ?? [], sessionSort, (session) =>
      Boolean((threadsQuery.data ?? []).some((thread) => thread.sessionId === session.sessionId && isRunningThread(thread))),
    )
    return sessions.map((session) => toSessionItem(session))
  }, [sessionSort, sessionsQuery.data, threadsQuery.data])

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
        commands={boundCommands(true)}
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
        items={sessionItems}
        sort={sessionSort}
        onSortChange={onSessionSortChange}
        emptyText="当前 Chat 暂无 Session"
        onClose={() => setSessionModalOpen(false)}
        onSelect={(selectedSessionId) => {
          const session = (sessionsQuery.data ?? []).find((item) => item.sessionId === selectedSessionId)
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

function isRunningThread(thread: HarnessThreadDTO): boolean {
  return thread.status === 'RUNNING' || thread.status === 'WAITING' || thread.status === 'RETRYING' || Boolean(thread.processing)
}

function toSessionItem(session: HarnessSessionDTO) {
  return {
    id: session.sessionId,
    title: session.title || session.sessionId,
    subtitle: `Main ${session.mainThreadId}`,
  }
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
