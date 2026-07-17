import { useNavigate, useParams } from 'react-router-dom'
import { ChatPanel } from '@/features/ai/ChatPanel'
import { useAgentSessionController } from '@/features/ai/useAgentSessionController'

export function AgentSessionPage() {
  const { sessionId = '' } = useParams()
  const navigate = useNavigate()
  const controller = useAgentSessionController(sessionId)

  return (
    <ChatPanel
      sessions={controller.sessions}
      agentsById={controller.agentsById}
      activeSessionId={sessionId}
      title={controller.title}
      onBack={() => navigate('/sessions')}
      session={controller.session}
      agent={controller.agent}
      timeline={controller.timeline}
      runs={controller.runs}
      activeRun={controller.activeRun}
      messagesLoading={controller.messagesLoading}
      messagesError={controller.messagesError}
      bodyRef={controller.bodyRef}
      draft={controller.draft}
      pending={controller.pending}
      disabled={controller.disabled}
      observability={controller.observability}
      onDraftChange={controller.setDraft}
      onSubmit={controller.submitMessage}
    />
  )
}
