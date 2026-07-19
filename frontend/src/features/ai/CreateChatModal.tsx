import type { FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/features/ai/AiConsoleModalLayout'
import type { AgentDefinitionDTO } from '@/shared/api/contracts'

export function CreateChatModal({
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
            <span>Title（可选）</span>
            <input value={title} onChange={(event) => onTitleChange(event.target.value)} placeholder="Chat 标题" />
          </label>
          <label className="form-group">
            <span>Default Agent（可选）</span>
            <select value={selectedAgentId} onChange={(event) => onSelectAgent(event.target.value)}>
              <option value="">（无）</option>
              {agents.map((agent) => (
                <option key={String(agent.id)} value={String(agent.id)}>
                  {agent.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={pending}>
            确认创建
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}
