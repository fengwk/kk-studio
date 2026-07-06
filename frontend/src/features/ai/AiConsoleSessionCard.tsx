import { ChevronRight, MessageSquare, Pencil, Trash2 } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { formatBackendDate } from '@/features/ai/ai-console-utils'
import type { AgentDefinitionDTO, AgentSessionDTO } from '@/shared/api/contracts'

export function SessionCard({
  session,
  agent,
  onEdit,
  onDelete,
  deletePending,
}: {
  session: AgentSessionDTO
  agent?: AgentDefinitionDTO
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
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
            <p>{agent?.name || session.agentName}</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        <MetaRow label="Status" value={session.status} />
        <MetaRow label="Agent" value={session.agentName} />
        <MetaRow label="Updated" value={formatBackendDate(session.updateTime)} />
      </div>
      <div className="chat-card-foot split">
        <button
          className="action-enter-btn green"
          type="button"
          aria-label={`进入会话 ${sessionLabel}`}
          onClick={() => navigate(`/agent/sessions/${session.sessionId}`)}
        >
          <ChevronRight aria-hidden="true" />
          进入会话
        </button>
        <button className="action-enter-btn" type="button" aria-label={`编辑 Chat ${sessionLabel}`} onClick={onEdit}>
          <Pencil aria-hidden="true" />
          编辑
        </button>
        <button className="action-enter-btn danger" type="button" aria-label={`删除 Chat ${sessionLabel}`} onClick={onDelete} disabled={deletePending}>
          <Trash2 aria-hidden="true" />
          删除
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
