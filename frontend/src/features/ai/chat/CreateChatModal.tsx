import { type FormEventHandler } from 'react'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select } from '@/shared/ui/controls/Select'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { useI18n } from '@/shared/i18n'

export function CreateChatModal({
  open,
  mode = 'create',
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
  mode?: 'create' | 'edit'
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

  const modalTitle = mode === 'edit' ? t('ai.chat.edit') : t('ai.chat.create')
  const submitLabel =
    mode === 'edit' ? t('ai.catalog.action.saveChanges') : t('ai.catalog.action.confirmCreate')

  return (
    <Dialog
      className="create-chat-modal-card"
      title={modalTitle}
      pending={pending}
      onClose={onClose}
    >
      <form className="modal-card-form" aria-label={modalTitle} onSubmit={onSubmit} noValidate>
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
            <Select
              aria-label={t('ai.chat.agent')}
              value={selectedAgentName}
              placeholder={t('ai.chat.selectAgent')}
              options={agents.map((agent) => ({ value: agent.name, label: agent.name }))}
              onChange={onSelectAgent}
            />
          </label>
        </div>
        <div className="modal-footer">
          <Button variant="ghost" onClick={onClose} disabled={pending}>
            {t('shared.cancel')}
          </Button>
          <Button type="submit" loading={pending}>
            {submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

export { CreateChatModal as ChatFormModal }
