import { useId, useMemo, type FormEventHandler } from 'react'
import { Switch } from '@/shared/ui/controls/Switch'
import { useQuery } from '@tanstack/react-query'
import { Button } from '@/shared/ui/controls/Button'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select, type SelectOption } from '@/shared/ui/controls/Select'
import { useFieldErrorId } from '@/shared/ui/controls/field-error'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { environmentService } from '@/features/environment/environment-service'
import { AgentSelect } from '@/features/thread/AgentSelect'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type { AgentDTO } from '@/shared/api/generated/contracts'
import '@/features/chat/create-chat-modal.css'

export function CreateChatModal({
  open,
  mode = 'create',
  agents,
  agentsLoading = false,
  agentsError = false,
  agentsReady = true,
  selectedAgentName,
  title,
  yoloEnabled,
  selectedEnvironmentName,
  pending,
  formError = '',
  nameError = '',
  agentError = '',
  onClose,
  onSelectAgent,
  onTitleChange,
  onYoloChange,
  onSelectEnvironment,
  onSubmit,
}: {
  open: boolean
  mode?: 'create' | 'edit'
  agents: readonly AgentDTO[]
  /** Agent 目录读取中：保留当前名称，不提前宣称不可用。 */
  agentsLoading?: boolean
  /** Agent 目录读取失败：目录内容未知，不据此判定已保存的 Agent 不可用。 */
  agentsError?: boolean
  /** Agent 目录已成功读取：只有此时才能确认名称不在目录里。 */
  agentsReady?: boolean
  selectedAgentName: string
  title: string
  yoloEnabled: boolean
  selectedEnvironmentName: string | null
  pending: boolean
  formError?: string
  nameError?: string
  agentError?: string
  onClose: () => void
  onSelectAgent: (agentName: string) => void
  onTitleChange: (title: string) => void
  onYoloChange: (yolo: boolean) => void
  onSelectEnvironment: (environmentName: string | null) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  const { t } = useI18n()
  const nameId = useId()
  const yoloDescriptionId = useId()
  const nameErrorId = useFieldErrorId(nameError)
  const agentErrorId = useFieldErrorId(agentError)

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
        label: environmentsQuery.isSuccess
          ? `${selectedEnvironmentName} · ${t('ai.chat.environmentUnavailable')}`
          : selectedEnvironmentName,
      })
    }
    return list
  }, [environmentsQuery.data, environmentsQuery.isSuccess, selectedEnvironmentName, t])

  const environmentErrorId = useFieldErrorId(
    environmentsQuery.isError ? t('ai.chat.environmentLoadFailed') : undefined,
  )

  if (!open) {
    return null
  }

  const modalTitle = mode === 'edit' ? t('ai.chat.edit') : t('ai.chat.create')
  const submitLabel =
    mode === 'edit' ? t('ai.catalog.action.saveChanges') : t('ai.catalog.action.confirmCreate')

  return (
    <Dialog
      className="create-chat-modal-card modal-touch-target"
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
          <div className={`form-group${nameError ? ' is-error' : ''}`}>
            <FieldLabel required htmlFor={nameId}>{t('ai.catalog.form.name')}</FieldLabel>
            <input
              id={nameId}
              value={title}
              onChange={(event) => onTitleChange(event.target.value)}
              placeholder={t('ai.chat.namePlaceholder')}
              disabled={pending}
              aria-invalid={nameError ? true : undefined}
              aria-describedby={nameErrorId}
            />
            {nameError ? <FieldError id={nameErrorId} role="alert">{nameError}</FieldError> : null}
          </div>
          <div className={`form-group${agentError ? ' is-error' : ''}`}>
            <FieldLabel required>{t('ai.chat.agent')}</FieldLabel>
            <AgentSelect
              agents={agents}
              value={selectedAgentName}
              onChange={onSelectAgent}
              aria-label={t('ai.chat.agent')}
              aria-invalid={agentError ? true : undefined}
              errorId={agentErrorId}
              disabled={pending}
              loading={agentsLoading}
              catalogError={agentsError}
              catalogReady={agentsReady}
              required
            />
            {agentError ? <FieldError id={agentErrorId} role="alert">{agentError}</FieldError> : null}
          </div>
          <div className="form-group">
            <div className="create-chat-yolo-row">
              <div className="create-chat-yolo-text">
                <FieldLabel>{t('projects.yolo')}</FieldLabel>
                <span id={yoloDescriptionId} className="create-chat-yolo-description">
                  {t('ai.chat.yoloDescription')}
                </span>
              </div>
              <Switch
                checked={yoloEnabled}
                onChange={onYoloChange}
                label={t('projects.yolo')}
                aria-describedby={yoloDescriptionId}
                disabled={pending}
              />
            </div>
          </div>
          <div className={`form-group${environmentsQuery.isError ? ' is-error' : ''}`}>
            <FieldLabel>{t('ai.chat.defaultEnvironment')}</FieldLabel>
            <Select
              aria-label={t('ai.chat.defaultEnvironment')}
              value={selectedEnvironmentName ?? ''}
              placeholder={t('ai.chat.selectEnvironment')}
              options={environmentOptions}
              onChange={(value) => onSelectEnvironment(value ? value : null)}
              disabled={pending}
              aria-invalid={environmentsQuery.isError}
              aria-describedby={environmentErrorId}
            />
            {environmentsQuery.isLoading ? (
              <span className="field-hint" role="status" aria-live="polite">
                {t('ai.common.loadingEnvironment')}
              </span>
            ) : null}
            {environmentsQuery.isError ? (
              <FieldError id={environmentErrorId} role="alert">
                {t('ai.chat.environmentLoadFailed')}
              </FieldError>
            ) : null}
          </div>
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
