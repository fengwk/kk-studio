import { ArrowLeft, MessageSquare, Plus } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { AgentDefinitionDTO, HarnessThreadDTO } from '@/shared/api/contracts'

export function ChatSidebar({
  threads,
  agentsById,
  activeThreadId,
  title,
  onBack,
}: {
  threads: HarnessThreadDTO[]
  agentsById: Map<string, AgentDefinitionDTO>
  activeThreadId: string
  title: string
  onBack: () => void
}) {
  return (
    <aside className="chat-sidebar">
      <div className="sidebar-header">
        <button className="sidebar-icon-btn" type="button" onClick={onBack} title="返回列表">
          <ArrowLeft aria-hidden="true" />
        </button>
        <h1>{title}</h1>
        <Link className="sidebar-icon-btn" to="/threads" title="新建对话">
          <Plus aria-hidden="true" />
        </Link>
      </div>
      <div className="chat-list">
        {threads.map((item) => {
          const agent = item.agentDefinitionId ? agentsById.get(item.agentDefinitionId) : undefined
          const label = item.sessionTitle || agent?.name || item.threadId
          return (
            <Link
              key={item.threadId}
              className={`chat-item ${item.threadId === activeThreadId ? 'active' : ''}`}
              to={`/threads/${encodeURIComponent(item.threadId)}`}
            >
              <MessageSquare aria-hidden="true" />
              <span>{label}</span>
            </Link>
          )
        })}
      </div>
    </aside>
  )
}
