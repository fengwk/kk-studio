import { ChevronRight, MessageSquare } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { formatBackendDate } from '@/features/ai/ai-console-utils'
import type { AgentDefinitionDTO, HarnessThreadDTO } from '@/shared/api/contracts'

export function ThreadCard({
  thread,
  agent,
}: {
  thread: HarnessThreadDTO
  agent?: AgentDefinitionDTO
}) {
  const navigate = useNavigate()
  const label = thread.sessionTitle || thread.threadId
  const agentLabel = agent?.name || thread.agentDefinitionId || 'agent'
  return (
    <article className="info-card">
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <MessageSquare aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3>{thread.sessionTitle || 'Untitled Chat'}</h3>
            <p>{agentLabel}</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        <MetaRow label="Agent" value={agentLabel} />
        <MetaRow label="Updated" value={formatBackendDate(thread.updateTime)} />
      </div>
      <div className="chat-card-foot split">
        <button
          className="action-enter-btn green"
          type="button"
          aria-label={`进入对话 ${label}`}
          onClick={() => navigate(`/threads/${encodeURIComponent(thread.threadId)}`)}
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
