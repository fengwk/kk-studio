import { ChevronRight, MessageSquare } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { formatBackendDate } from '@/features/ai/ai-console-utils'
import type { AgentDefinitionDTO, HarnessSessionDTO } from '@/shared/api/contracts'

export function SessionCard({
  session,
  agent,
}: {
  session: HarnessSessionDTO
  agent?: AgentDefinitionDTO
}) {
  const navigate = useNavigate()
  const sessionLabel = session.title || session.sessionId
  return (
    <article className="info-card">
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <MessageSquare aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3>{session.title || 'Untitled Chat'}</h3>
            <p>{agent?.name || session.agentDefinitionId}</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        <MetaRow label="Agent" value={agent?.name || session.agentDefinitionId} />
        <MetaRow label="Updated" value={formatBackendDate(session.updateTime)} />
      </div>
      <div className="chat-card-foot split">
        <button
          className="action-enter-btn green"
          type="button"
          aria-label={`进入会话 ${sessionLabel}`}
          onClick={() => navigate(`/sessions/${encodeURIComponent(session.sessionId)}`)}
        >
          <ChevronRight aria-hidden="true" />
          进入会话
        </button>
      </div>
    </article>
  )
}

function MetaRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="meta-row">
      <span className="lbl">{label}</span>
      <span className="val">{value}</span>
    </div>
  )
}
