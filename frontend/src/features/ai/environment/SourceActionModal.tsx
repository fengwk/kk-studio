import { useState } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { NumberInput } from '@/shared/ui/console/NumberInput'
import { useI18n } from '@/shared/i18n'
import type { EnvironmentSkillSourceDTO } from '@/shared/api/contracts/ai-environment'

export type SourceActionType = 'REFRESH' | 'INSTALL' | 'UPDATE'

export interface SourceActionModalProps {
  action: SourceActionType
  source: EnvironmentSkillSourceDTO
  pending: boolean
  error?: string | null
  onClose: () => void
  onConfirm: (timeoutMillis: number) => void
}

function getDefaultTimeoutMillis(action: SourceActionType): number {
  switch (action) {
    case 'REFRESH':
      return 60_000
    case 'INSTALL':
    case 'UPDATE':
      return 300_000
  }
}

export function SourceActionModal({
  action,
  source,
  pending,
  error,
  onClose,
  onConfirm,
}: SourceActionModalProps) {
  const { t } = useI18n()
  const defaultTimeout = getDefaultTimeoutMillis(action)
  const [timeoutMillisStr, setTimeoutMillisStr] = useState<string>(String(defaultTimeout))
  const [validationError, setValidationError] = useState<string | null>(null)

  const actionName = action === 'REFRESH'
    ? t('ai.environment.action.refresh')
    : action === 'INSTALL'
      ? t('ai.environment.action.install')
      : t('ai.environment.action.update')

  const title = t('ai.environment.action.dialogTitle', { action: actionName })
  const targetDesc = source.type === 'path'
    ? (source.path || source.sourceId)
    : (source.gitUrl || source.sourceId)
  const description = t('ai.environment.action.dialogDescription', { action: actionName, target: targetDesc })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setValidationError(null)

    const raw = timeoutMillisStr.trim()
    const parsed = Number(raw)
    if (!raw || !Number.isSafeInteger(parsed) || parsed <= 0) {
      setValidationError(t('ai.environment.action.timeoutInvalid'))
      return
    }

    onConfirm(parsed)
  }

  const displayError = validationError ?? error

  return (
    <ModalBackdrop onClose={pending ? () => undefined : onClose}>
      <div
        className="modal-card env-action-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <ModalHeader title={title} onClose={onClose} closeDisabled={pending} />
        <form onSubmit={handleSubmit}>
          <div className="modal-body">
            <p className="confirm-modal-description">{description}</p>
            <div className="form-group">
              <FieldLabel required>{t('ai.environment.action.timeoutMillis')}</FieldLabel>
              <NumberInput
                value={timeoutMillisStr}
                onChange={(val) => {
                  setTimeoutMillisStr(val)
                  setValidationError(null)
                }}
                step={5000}
                disabled={pending}
                aria-label={t('ai.environment.action.timeoutMillis')}
              />
            </div>
            {displayError ? (
              <p className="field-error" role="alert">
                {displayError}
              </p>
            ) : null}
          </div>
          <div className="modal-footer">
            <button
              type="button"
              className="ghost-btn"
              onClick={onClose}
              disabled={pending}
            >
              {t('shared.cancel')}
            </button>
            <button
              type="submit"
              className="btn-primary"
              disabled={pending}
            >
              {t('shared.confirm')}
            </button>
          </div>
        </form>
      </div>
    </ModalBackdrop>
  )
}
