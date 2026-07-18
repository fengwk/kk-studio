import { useNavigate, useParams } from 'react-router-dom'
import { ChatPanel } from '@/features/ai/ChatPanel'
import { useAgentThreadController } from '@/features/ai/useAgentThreadController'

export function AgentThreadPage() {
  const { threadId = '' } = useParams()
  const navigate = useNavigate()
  const controller = useAgentThreadController(threadId)

  return (
    <ChatPanel
      threads={controller.threads}
      agentsById={controller.agentsById}
      activeThreadId={threadId}
      title={controller.title}
      onBack={() => navigate('/threads')}
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
    />
  )
}
