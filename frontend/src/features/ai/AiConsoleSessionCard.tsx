import { ChevronRight, MessageSquare } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { formatBackendDate } from '@/features/ai/ai-console-utils'
import type { HarnessSessionDTO } from '@/shared/api/contracts'

export function SessionCard({
  session,
}: {
  session: HarnessSessionDTO
}) {
  const navigate = useNavigate()
  const label = session.title || session.sessionId
  return (
    <article className="info-card">
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <MessageSquare aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3>{session.title || 'Untitled Chat'}</h3>
            <p>Session</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        <MetaRow label="Main Thread" value={session.mainThreadId} />
        <MetaRow label="Updated" value={formatBackendDate(session.updateTime)} />
      </div>
      <div className="chat-card-foot split">
        <button
          className="action-enter-btn green"
          type="button"
          aria-label={`进入会话 ${label}`}
          onClick={() => navigate(`/sessions/${encodeURIComponent(session.sessionId)}`)}
        >
          <ChevronRight aria-hidden="true" />
          进入对话
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
