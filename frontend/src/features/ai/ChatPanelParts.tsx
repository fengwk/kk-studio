import { ArrowLeft, MessageSquare, Plus } from 'lucide-react'
import { ChatRunStatus } from '@/features/ai/ChatRunStatus'
import { Link } from 'react-router-dom'
import type { AgentDefinitionDTO, HarnessRunDTO, HarnessSessionDTO } from '@/shared/api/contracts'

export function ChatSidebar({
  sessions,
  agentsById,
  activeSessionId,
  title,
  onBack,
}: {
  sessions: HarnessSessionDTO[]
  agentsById: Map<string, AgentDefinitionDTO>
  activeSessionId: string
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
        <Link className="sidebar-icon-btn" to="/sessions" title="新建会话">
          <Plus aria-hidden="true" />
        </Link>
      </div>
      <div className="chat-list">
        {sessions.map((item) => {
          const agent = agentsById.get(item.agentDefinitionId)
          return (
            <Link
              key={item.sessionId}
              className={`chat-item ${item.sessionId === activeSessionId ? 'active' : ''}`}
              to={`/sessions/${encodeURIComponent(item.sessionId)}`}
            >
              <MessageSquare aria-hidden="true" />
              <span>{item.title || agent?.name || item.sessionId}</span>
            </Link>
          )
        })}
      </div>
    </aside>
  )
}

export function ChatRuntimeBarStatus({
  runs,
  activeRun,
}: {
  runs: HarnessRunDTO[]
  activeRun: boolean
}) {
  return <ChatRunStatus runs={runs} activeRun={activeRun} />
}
