import { useEffect, useState, type FormEventHandler } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { Select } from '@/shared/ui/console/Select'
import { EnvironmentWorkspacePanel } from '@/features/ai/chat/EnvironmentWorkspacePanel'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { useI18n } from '@/shared/i18n'

export function CreateChatModal({
  open,
  agents,
  environments = [],
  selectedAgentName,
  selectedWorkspacePath = null,
  title,
  pending,
  formError = '',
  nameError = '',
  onClose,
  onSelectAgent,
  onSelectWorkspacePath,
  onTitleChange,
  onSubmit,
}: {
  open: boolean
  agents: AgentDefinitionDTO[]
  environments?: EnvironmentCardDTO[]
  selectedAgentName: string
  selectedWorkspacePath?: string | null
  title: string
  pending: boolean
  formError?: string
  nameError?: string
  onClose: () => void
  onSelectAgent: (agentName: string) => void
  onSelectWorkspacePath?: (workspacePath: string | null) => void
  onTitleChange: (title: string) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  const { t } = useI18n()
  const [environmentPanelOpen, setEnvironmentPanelOpen] = useState(false)

  useEffect(() => {
    if (!open) {
      setEnvironmentPanelOpen(false)
    }
  }, [open])

  if (!open) {
    return null
  }

  const selectedAgent = agents.find((agent) => agent.name === selectedAgentName)
  const boundEnvironment = selectedAgent?.environmentId
    ? environments.find((env) => env.id === selectedAgent.environmentId)
    : undefined

  const triggerLabel = boundEnvironment
    ? selectedWorkspacePath
      ? `${boundEnvironment.name} · ${workspaceDisplayPath(selectedWorkspacePath)}`
      : `${boundEnvironment.name} · ${t('ai.chat.noneEnvironment')}`
    : t('ai.chat.noneEnvironment')

  return (
    <ModalBackdrop onClose={onClose}>
      <form
        className="modal-card create-chat-modal-card"
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
            <Select
              aria-label={t('ai.chat.agent')}
              value={selectedAgentName}
              placeholder={t('ai.chat.selectAgent')}
              options={agents.map((agent) => ({ value: agent.name, label: agent.name }))}
              onChange={onSelectAgent}
            />
          </label>
          <div className="form-group">
            <FieldLabel>{t('ai.chat.environment')}</FieldLabel>
            <button
              type="button"
              className="environment-binding-trigger"
              aria-label={t('ai.chat.environment')}
              aria-expanded={environmentPanelOpen}
              disabled={pending || !boundEnvironment}
              onClick={() => setEnvironmentPanelOpen((current) => !current)}
            >
              {triggerLabel}
            </button>
            {!boundEnvironment && (
              <span className="inline-hint">{t('ai.chat.noEnvironmentBound')}</span>
            )}
          </div>
          {environmentPanelOpen ? (
            <EnvironmentWorkspacePanel
              environment={boundEnvironment}
              current={selectedWorkspacePath}
              pending={pending}
              onClose={() => setEnvironmentPanelOpen(false)}
              onSelect={(workspacePath) => {
                onSelectWorkspacePath?.(workspacePath)
                setEnvironmentPanelOpen(false)
              }}
            />
          ) : null}
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

function workspaceDisplayPath(path: string): string {
  return path === '.' ? '@/' : `@/${path}`
}
