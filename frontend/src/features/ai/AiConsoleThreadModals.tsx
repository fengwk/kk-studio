import type { FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/features/ai/AiConsoleModalLayout'
import type { AgentDefinitionDTO } from '@/shared/api/contracts'

export function CreateThreadModal({
  open,
  agents,
  selectedAgentId,
  title,
  pending,
  onClose,
  onSelectAgent,
  onTitleChange,
  onSubmit,
}: {
  open: boolean
  agents: AgentDefinitionDTO[]
  selectedAgentId: string
  title: string
  pending: boolean
  onClose: () => void
  onSelectAgent: (agentId: string) => void
  onTitleChange: (title: string) => void
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
            <select value={selectedAgentId} onChange={(event) => onSelectAgent(event.target.value)} required>
              {agents.map((agent) => (
                <option key={agent.id} value={String(agent.id)}>
                  {agent.name}
                </option>
              ))}
            </select>
          </label>
          <label className="form-group">
            <span>Title</span>
            <input value={title} onChange={(event) => onTitleChange(event.target.value)} placeholder="会话标题" />
          </label>
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={!selectedAgentId || pending}>
            确认创建
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}
