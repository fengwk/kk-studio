import type { FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { FormSelect } from '@/shared/ui/console/FormSelect'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { useI18n } from '@/shared/i18n'

export function CreateChatModal({
  open,
  agents,
  selectedAgentName,
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
  selectedAgentName: string
  title: string
  pending: boolean
  formError?: string
  nameError?: string
  onClose: () => void
  onSelectAgent: (agentName: string) => void
  onTitleChange: (title: string) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  const { t } = useI18n()
  if (!open) {
    return null
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <form
        className="modal-card"
        aria-label={t('ai.chat.create')}
        onSubmit={onSubmit}
        onMouseDown={(event) => event.stopPropagation()}
        noValidate
      >
        <ModalHeader title={t('ai.chat.create')} onClose={onClose} />
        <div className="modal-body">
          {formError ? (
            <div className="form-error-banner" role="alert">
              {formError}
            </div>
          ) : null}
          <label className={`form-group${nameError ? ' is-error' : ''}`}>
            <FieldLabel required>{t('ai.catalog.form.name')}</FieldLabel>
            <input
              value={title}
              onChange={(event) => onTitleChange(event.target.value)}
              placeholder={t('ai.chat.namePlaceholder')}
            />
            {nameError ? <span className="field-error">{nameError}</span> : null}
          </label>
          <label className="form-group">
            <FieldLabel required>{t('ai.chat.agent')}</FieldLabel>
            <FormSelect
              aria-label={t('ai.chat.agent')}
              value={selectedAgentName}
              placeholder={t('ai.chat.selectAgent')}
              options={agents.map((agent) => ({ value: agent.name, label: agent.name }))}
              onChange={onSelectAgent}
            />
          </label>
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={pending}>
            {t('ai.catalog.action.confirmCreate')}
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}
