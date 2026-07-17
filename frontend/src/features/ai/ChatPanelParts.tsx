import { ArrowLeft, Clock3, MessageSquare, Plus, Send, Square, StepForward } from 'lucide-react'
import type { KeyboardEvent } from 'react'
import { ChatRunStatus } from '@/features/ai/ChatRunStatus'
import { Link } from 'react-router-dom'
import type { SessionTimeline } from '@/features/ai/session-events'
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

export function ChatRuntimeBar({
  session,
  agent,
  timeline,
  runs,
  activeRun,
}: {
  session?: HarnessSessionDTO
  agent?: AgentDefinitionDTO
  timeline: SessionTimeline
  runs: HarnessRunDTO[]
  activeRun: boolean
}) {
  const runtimeProvider = timeline.runtimeContext.provider || agent?.defaultProviderName || '-'
  const runtimeModel = timeline.runtimeContext.model || agent?.defaultModelName || '-'
  const runtimeVariant = timeline.runtimeContext.variant || agent?.defaultVariant || '-'
  const agentLabel = agent?.name || session?.agentDefinitionId || 'Agent'

  return (
    <div className="chat-runtime-bar">
      <div>
        <strong>{agentLabel}</strong>
        <span>
          {runtimeProvider} / {runtimeModel} / {runtimeVariant}
        </span>
      </div>
      <ChatRunStatus runs={runs} activeRun={activeRun} />
    </div>
  )
}

export function ChatComposer({
  draft,
  activeRun,
  pending,
  disabled,
  controlsPending,
  onDraftChange,
  onSubmit,
  onSteer,
  onFollowUp,
  onAbort,
}: {
  draft: string
  activeRun: boolean
  pending: boolean
  disabled: boolean
  controlsPending: boolean
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onSteer: () => void
  onFollowUp: () => void
  onAbort: () => void
}) {
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      onSubmit()
    }
  }

  return (
    <div className="chat-input-area">
      <div className="chat-input-container">
        <textarea
          value={draft}
          onChange={(event) => onDraftChange(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="给 AI 发送消息..."
          disabled={disabled || pending}
        />
        <div className="chat-input-tools">
          <div className="run-hint">
            <Clock3 aria-hidden="true" />
            <span>{activeRun ? 'running' : 'idle'}</span>
          </div>
          <button className="control-btn" type="button" aria-label="插入指令" onClick={onSteer} disabled={!activeRun || !draft.trim() || controlsPending}>
            Steer
          </button>
          <button className="control-btn" type="button" aria-label="排队追问" onClick={onFollowUp} disabled={!draft.trim() || controlsPending}>
            <StepForward aria-hidden="true" />
          </button>
          <button className="control-btn danger" type="button" aria-label="终止运行" onClick={onAbort} disabled={!activeRun || controlsPending}>
            <Square aria-hidden="true" />
          </button>
          <button className="send-btn" type="button" aria-label="发送消息" onClick={onSubmit} disabled={!draft.trim() || pending || disabled || activeRun}>
            <Send aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>
  )
}
