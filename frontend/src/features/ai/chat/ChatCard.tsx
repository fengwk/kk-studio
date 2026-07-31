import { ChevronRight, MessageSquare } from 'lucide-react'
import { useNavigate } from 'react-router'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'

export function ChatCard({
  chat,
  agents,
}: {
  chat: ChatDTO
  agents: AgentDefinitionDTO[]
}) {
  const navigate = useNavigate()
  const label = chat.title || chat.id
  const defaultAgent = chat.defaultAgentId
    ? agents.find((agent) => String(agent.id) === String(chat.defaultAgentId))
    : undefined
  const agentLabel = defaultAgent
      ? defaultAgent.name
      : '（已删除/缺失）'

  return (
    <article className="info-card">
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <MessageSquare aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3>{chat.title || 'Untitled Chat'}</h3>
            <p>Chat</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        <MetaRow label="Default Agent" value={agentLabel} />
        <MetaRow label="Updated" value={formatBackendDate(chat.updateTime)} />
      </div>
      <div className="chat-card-foot split">
        <button
          className="action-enter-btn green"
          type="button"
          aria-label={`进入 Chat ${label}`}
          onClick={() => navigate(`/chats/${encodeURIComponent(chat.id)}`)}
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
