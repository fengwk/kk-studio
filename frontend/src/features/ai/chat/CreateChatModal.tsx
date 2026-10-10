import { useMemo, type FormEventHandler } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select, type SelectOption } from '@/shared/ui/controls/Select'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { environmentService } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

export function CreateChatModal({
  open,
  mode = 'create',
  agents,
  selectedAgentName,
  title,
  yoloEnabled = false,
  selectedEnvironmentName = null,
  pending,
  formError = '',
  nameError = '',
  onClose,
  onSelectAgent,
  onTitleChange,
  onYoloChange,
  onSelectEnvironment,
  onSubmit,
}: {
  open: boolean
  mode?: 'create' | 'edit'
  agents: AgentDefinitionDTO[]
  selectedAgentName: string
  title: string
  yoloEnabled?: boolean
  selectedEnvironmentName?: string | null
  pending: boolean
  formError?: string
  nameError?: string
  onClose: () => void
  onSelectAgent: (agentName: string) => void
  onTitleChange: (title: string) => void
  onYoloChange?: (yolo: boolean) => void
  onSelectEnvironment?: (environmentName: string | null) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  const { t } = useI18n()

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: open,
  })

  const environmentOptions = useMemo<SelectOption[]>(() => {
    const list: SelectOption[] = [
      { value: '', label: t('ai.chat.noEnvironment') },
    ]
    const envs = environmentsQuery.data ?? []
    for (const env of envs) {
      list.push({ value: env.name, label: env.name })
    }
    if (selectedEnvironmentName && !envs.some((e) => e.name === selectedEnvironmentName)) {
      list.push({
        value: selectedEnvironmentName,
        label: `${selectedEnvironmentName} ${t('ai.chat.environmentUnavailable')}`,
      })
    }
    return list
  }, [environmentsQuery.data, selectedEnvironmentName, t])

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
              disabled={pending}
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
              disabled={pending}
            />
          </label>
          <div className="form-group">
            <div className="settings-row">
              <div className="settings-row-text">
                <FieldLabel>{t('projects.yolo')}</FieldLabel>
                <span className="settings-row-description">
                  {t('ai.chat.yoloDescription')}
                </span>
              </div>
              <button
                type="button"
                role="switch"
                className="settings-switch"
                aria-checked={yoloEnabled}
                aria-label={t('projects.yolo')}
                disabled={pending}
                onClick={() => onYoloChange?.(!yoloEnabled)}
              >
                <span className="settings-switch-thumb" aria-hidden="true" />
              </button>
            </div>
          </div>
          <label className="form-group">
            <FieldLabel>{t('ai.chat.defaultEnvironment')}</FieldLabel>
            <Select
              aria-label={t('ai.chat.defaultEnvironment')}
              value={selectedEnvironmentName ?? ''}
              placeholder={t('ai.chat.selectEnvironment')}
              options={environmentOptions}
              onChange={(value) => onSelectEnvironment?.(value ? value : null)}
              disabled={pending}
            />
            {environmentsQuery.isError ? (
              <span className="field-error" role="status">
                {t('ai.chat.environmentLoadFailed')}
              </span>
            ) : null}
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
