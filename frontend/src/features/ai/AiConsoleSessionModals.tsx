import type { FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/features/ai/AiConsoleModalLayout'
import type { AgentDefinitionDTO } from '@/shared/api/contracts'

export function CreateSessionModal({
  open,
  agents,
  selectedAgentName,
  sessionTitle,
  pending,
  onClose,
  onSelectAgent,
  onSessionTitleChange,
  onSubmit,
}: {
  open: boolean
  agents: AgentDefinitionDTO[]
  selectedAgentName: string
  sessionTitle: string
  pending: boolean
  onClose: () => void
  onSelectAgent: (agentName: string) => void
  onSessionTitleChange: (title: string) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  if (!open) {
    return null
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <form className="modal-card" aria-label="新建 Chat" onSubmit={onSubmit} onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title="新建 Chat" onClose={onClose} />
        <div className="modal-body">
          <label className="form-group">
            <span>Agent</span>
            <select value={selectedAgentName} onChange={(event) => onSelectAgent(event.target.value)} required>
              {agents.map((agent) => (
                <option key={agent.id} value={agent.name}>
                  {agent.name}
                </option>
              ))}
            </select>
          </label>
          <label className="form-group">
            <span>Title</span>
            <input value={sessionTitle} onChange={(event) => onSessionTitleChange(event.target.value)} placeholder="会话标题" />
          </label>
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={!selectedAgentName || pending}>
            确认创建
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}

export function EditSessionModal({
  open,
  title,
  pending,
  onClose,
  onTitleChange,
  onSubmit,
}: {
  open: boolean
  title: string
  pending: boolean
  onClose: () => void
  onTitleChange: (title: string) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  if (!open) {
    return null
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <form className="modal-card" aria-label="编辑 Chat" onSubmit={onSubmit} onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title="编辑 Chat" onClose={onClose} />
        <div className="modal-body">
          <label className="form-group">
            <span>Title</span>
            <input value={title} onChange={(event) => onTitleChange(event.target.value)} placeholder="会话标题" />
          </label>
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={pending}>
            保存修改
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}
