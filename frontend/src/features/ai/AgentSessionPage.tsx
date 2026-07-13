import { useNavigate, useParams } from 'react-router-dom'
import { ChatPanel } from '@/features/ai/ChatPanel'
import { useAgentSessionController } from '@/features/ai/useAgentSessionController'

export function AgentSessionPage() {
  const { workspaceId = '', sessionId = '' } = useParams()
  const navigate = useNavigate()
  const controller = useAgentSessionController(workspaceId, sessionId)

  return (
    <ChatPanel
      workspaceId={workspaceId}
      sessions={controller.sessions}
      agentsByName={controller.agentsByName}
      activeSessionId={sessionId}
      title={controller.title}
      onBack={() => navigate(`/workspaces/${encodeURIComponent(workspaceId)}/sessions`)}
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
      onDraftChange={controller.setDraft}
      onSubmit={controller.submitMessage}
    />
  )
}
