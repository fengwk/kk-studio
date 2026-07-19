import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { ChatPanel } from '@/features/ai/ChatPanel'
import { HistoryBranchPanel } from '@/features/ai/HistoryBranchPanel'
import { branchTarget } from '@/features/ai/session-entry-tree'
import type { ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import { useAgentThreadController } from '@/features/ai/useAgentThreadController'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function SessionThreadPage() {
  const { sessionId = '' } = useParams()
  const navigate = useNavigate()
  const sessionQuery = useQuery({
    queryKey: queryKeys.sessions.detail(sessionId),
    queryFn: () => harnessService.getSession(sessionId),
    enabled: Boolean(sessionId),
  })

  useEffect(() => {
    if (sessionQuery.data) {
      navigate(`/sessions/${encodeURIComponent(sessionId)}/threads/${encodeURIComponent(sessionQuery.data.mainThreadId)}`, {
        replace: true,
      })
    }
  }, [navigate, sessionId, sessionQuery.data])

  if (sessionQuery.error) {
    return <div className="thread-state danger">会话加载失败</div>
  }
  return <div className="thread-state">正在打开主线程…</div>
}

export function ThreadDeepLinkPage() {
  const { threadId = '' } = useParams()
  const navigate = useNavigate()
  const threadQuery = useQuery({
    queryKey: queryKeys.threads.detail(threadId),
    queryFn: () => harnessService.getThread(threadId),
    enabled: Boolean(threadId),
  })

  useEffect(() => {
    if (threadQuery.data) {
      navigate(
        `/sessions/${encodeURIComponent(threadQuery.data.sessionId)}/threads/${encodeURIComponent(threadId)}`,
        { replace: true },
      )
    }
  }, [navigate, threadId, threadQuery.data])

  if (threadQuery.error) {
    return <div className="thread-state danger">线程加载失败</div>
  }
  return <div className="thread-state">正在打开线程…</div>
}

export function AgentThreadPage() {
  const { sessionId = '', threadId = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const [historyBranchOpen, setHistoryBranchOpen] = useState(false)
  const initialDraft = (location.state as { draft?: string } | null)?.draft ?? ''
  const controller = useAgentThreadController(threadId, sessionId, initialDraft)
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
      navigate(
        `/sessions/${encodeURIComponent(sessionId)}/threads/${encodeURIComponent(nextThread.threadId)}`,
        { state: { draft: branchTarget(entry).draft } },
      )
    },
  })

  function closeHistoryBranchPanel() {
    setHistoryBranchOpen(false)
    branchMutation.reset()
  }

  function handleCommand(command: ThreadCommand) {
    if (command.id === 'tree') {
      setHistoryBranchOpen(true)
      return
    }
    controller.runCommand(command)
  }

  return (
    <>
      <ChatPanel
        threads={controller.threads}
        activeThreadId={threadId}
        sessionId={sessionId}
        mainThreadId={controller.session?.mainThreadId}
        title={controller.title}
        onBack={() => navigate('/sessions')}
        agent={controller.agent}
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
        onSubmit={controller.submitMessage}
        onCommand={handleCommand}
      />
      {historyBranchOpen ? (
        <HistoryBranchPanel
          entries={sessionEntriesQuery.data ?? []}
          currentHeadEntryId={controller.thread?.headEntryId}
          loading={sessionEntriesQuery.isLoading}
          queryError={sessionEntriesQuery.error}
          pending={branchMutation.isPending}
          creationError={branchMutation.error}
          onClose={closeHistoryBranchPanel}
          onCreate={(entry) => branchMutation.mutate(entry)}
        />
      ) : null}
    </>
  )
}
