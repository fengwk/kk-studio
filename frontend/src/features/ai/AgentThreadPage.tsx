import { useEffect } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { ChatPanel } from '@/features/ai/ChatPanel'
import { branchTarget } from '@/features/ai/session-entry-tree'
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

export function AgentThreadPage() {
  const { sessionId = '', threadId = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const initialDraft = (location.state as { draft?: string } | null)?.draft ?? ''
  const controller = useAgentThreadController(threadId, sessionId, initialDraft)
  const branchMutation = useMutation({
    mutationFn: (entry: HarnessSessionEntryDTO) => {
      const target = branchTarget(entry)
      if (!target.fromEntryId) {
        return Promise.reject(new Error('根节点不能作为可编辑消息分支'))
      }
      return harnessService.createSessionThread(sessionId, { fromEntryId: target.fromEntryId })
    },
    onSuccess: (nextThread, entry) => {
      navigate(
        `/sessions/${encodeURIComponent(sessionId)}/threads/${encodeURIComponent(nextThread.threadId)}`,
        { state: { draft: branchTarget(entry).draft } },
      )
    },
  })

  return (
    <ChatPanel
      threads={controller.threads}
      sessionEntries={controller.sessionEntries}
      activeThreadId={threadId}
      sessionId={sessionId}
      mainThreadId={controller.session?.mainThreadId}
      title={controller.title}
      threadStatus={controller.thread?.status}
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
      controlsPending={controller.controlsPending}
      actionError={controller.actionError}
      onDismissActionError={controller.dismissActionError}
      onDraftChange={controller.setDraft}
      onSubmit={controller.submitMessage}
      onCommand={controller.runCommand}
      onBranch={(entry) => branchMutation.mutate(entry)}
      branchPending={branchMutation.isPending}
      onStop={controller.stopThread}
      onRetry={controller.retryThread}
      stopPending={controller.stopPending}
      retryPending={controller.retryPending}
    />
  )
}
