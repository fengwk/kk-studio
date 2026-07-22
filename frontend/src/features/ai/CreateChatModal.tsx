import type { FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/features/ai/AiConsoleModalLayout'
import { FieldLabel } from '@/features/ai/FieldLabel'
import { FormSelect } from '@/features/ai/FormSelect'
import type { AgentDefinitionDTO } from '@/shared/api/contracts'

export function CreateChatModal({
  open,
  agents,
  selectedAgentId,
  title,
  pending,
  formError = '',
  nameError = '',
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
  formError?: string
  nameError?: string
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
      <form
        className="modal-card"
        aria-label="新建 Chat"
        onSubmit={onSubmit}
        onMouseDown={(event) => event.stopPropagation()}
        noValidate
      >
        <ModalHeader title="新建 Chat" onClose={onClose} />
        <div className="modal-body">
          {formError ? (
            <div className="form-error-banner" role="alert">
              {formError}
            </div>
          ) : null}
          <label className={`form-group${nameError ? ' is-error' : ''}`}>
            <FieldLabel required>Name</FieldLabel>
            <input
              value={title}
              onChange={(event) => onTitleChange(event.target.value)}
              placeholder="Chat 名称（可重名）"
            />
            {nameError ? <span className="field-error">{nameError}</span> : null}
          </label>
          <label className="form-group">
            <FieldLabel>Default Agent</FieldLabel>
            <FormSelect
              aria-label="Default Agent"
              value={selectedAgentId}
              placeholder="（无）"
              options={[
                { value: '', label: '（无）' },
                ...agents.map((agent) => ({ value: String(agent.id), label: agent.name })),
              ]}
              onChange={onSelectAgent}
            />
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
