import { useState } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { useI18n } from '@/shared/i18n'
import type {
  EnvironmentSkillSourceCreateDTO,
  EnvironmentSkillSourceDTO,
  EnvironmentSkillSourceUpdateDTO,
  SkillSourceType,
} from '@/shared/api/contracts/ai-environment'

export interface SourceEditModalProps {
  source?: EnvironmentSkillSourceDTO | null
  pending: boolean
  error?: string | null
  onClose: () => void
  onSubmit: (payload: {
    create?: EnvironmentSkillSourceCreateDTO
    update?: EnvironmentSkillSourceUpdateDTO
    sourceId?: string
  }) => void
}

export function SourceEditModal({
  source,
  pending,
  error,
  onClose,
  onSubmit,
}: SourceEditModalProps) {
  const { t } = useI18n()
  const isEditing = Boolean(source)
  const isDefaultSource = source?.defaultSource === true

  const [type, setType] = useState<SkillSourceType>(source?.type ?? 'path')
  const [path, setPath] = useState(source?.path ?? '')
  const [gitUrl, setGitUrl] = useState(source?.gitUrl ?? '')
  const [gitRef, setGitRef] = useState(source?.gitRef ?? '')
  const [scanPath, setScanPath] = useState(source?.scanPath ?? '')
  const [validationError, setValidationError] = useState<string | null>(null)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setValidationError(null)

    if (type === 'path') {
      const trimmedPath = path.trim()
      if (!trimmedPath) {
        setValidationError(t('ai.environment.sources.pathRequired'))
        return
      }
      if (isEditing && source) {
        onSubmit({
          sourceId: source.sourceId,
          update: {
            type: 'path',
            path: trimmedPath,
            gitUrl: null,
            gitRef: null,
            scanPath: null,
            expectedVersion: source.version,
          },
        })
      } else {
        onSubmit({
          create: {
            type: 'path',
            path: trimmedPath,
          },
        })
      }
    } else {
      const trimmedGitUrl = gitUrl.trim()
      if (!trimmedGitUrl) {
        setValidationError(t('ai.environment.sources.gitUrlRequired'))
        return
      }
      const trimmedGitRef = gitRef.trim()
      const trimmedScanPath = scanPath.trim()

      if (isEditing && source) {
        onSubmit({
          sourceId: source.sourceId,
          update: {
            type: 'git',
            gitUrl: trimmedGitUrl,
            gitRef: trimmedGitRef || null,
            scanPath: trimmedScanPath || null,
            path: null,
            expectedVersion: source.version,
          },
        })
      } else {
        onSubmit({
          create: {
            type: 'git',
            gitUrl: trimmedGitUrl,
            gitRef: trimmedGitRef || null,
            scanPath: trimmedScanPath || null,
          },
        })
      }
    }
  }

  const title = isEditing
    ? t('ai.environment.sources.edit')
    : t('ai.environment.sources.create')

  const effectiveError = validationError ?? error

  return (
    <ModalBackdrop onClose={pending ? () => undefined : onClose}>
      <div
        className="modal-card env-source-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <ModalHeader title={title} onClose={onClose} closeDisabled={pending} />
        <form onSubmit={handleSubmit}>
          <div className="modal-body">
            <div className="form-group">
              <FieldLabel required>{t('ai.environment.sources.type')}</FieldLabel>
              <div className="env-type-selector" role="radiogroup" aria-label={t('ai.environment.sources.type')}>
                <label className={`env-type-option${type === 'path' ? ' is-selected' : ''}`}>
                  <input
                    type="radio"
                    name="sourceType"
                    value="path"
                    checked={type === 'path'}
                    onChange={() => {
                      setType('path')
                      setValidationError(null)
                    }}
                    disabled={pending || isDefaultSource}
                  />
                  <span>{t('ai.environment.sources.typePath')}</span>
                </label>
                <label
                  className={`env-type-option${type === 'git' ? ' is-selected' : ''}${
                    isDefaultSource ? ' is-disabled' : ''
                  }`}
                  title={isDefaultSource ? t('ai.environment.sources.defaultTypeImmutable') : undefined}
                >
                  <input
                    type="radio"
                    name="sourceType"
                    value="git"
                    checked={type === 'git'}
                    onChange={() => {
                      if (!isDefaultSource) {
                        setType('git')
                        setValidationError(null)
                      }
                    }}
                    disabled={pending || isDefaultSource}
                  />
                  <span>{t('ai.environment.sources.typeGit')}</span>
                </label>
              </div>
              {isDefaultSource ? (
                <p className="field-hint">{t('ai.environment.sources.defaultTypeImmutable')}</p>
              ) : null}
            </div>

            {type === 'path' ? (
              <label className="form-group">
                <FieldLabel required>{t('ai.environment.sources.path')}</FieldLabel>
                <input
                  type="text"
                  value={path}
                  onChange={(e) => {
                    setPath(e.target.value)
                    setValidationError(null)
                  }}
                  placeholder={t('ai.environment.sources.pathPlaceholder')}
                  disabled={pending}
                  autoFocus
                />
              </label>
            ) : (
              <>
                <label className="form-group">
                  <FieldLabel required>{t('ai.environment.sources.gitUrl')}</FieldLabel>
                  <input
                    type="text"
                    value={gitUrl}
                    onChange={(e) => {
                      setGitUrl(e.target.value)
                      setValidationError(null)
                    }}
                    placeholder={t('ai.environment.sources.gitUrlPlaceholder')}
                    disabled={pending}
                    autoFocus
                  />
                </label>
                <label className="form-group">
                  <FieldLabel>{t('ai.environment.sources.gitRef')}</FieldLabel>
                  <input
                    type="text"
                    value={gitRef}
                    onChange={(e) => setGitRef(e.target.value)}
                    placeholder={t('ai.environment.sources.gitRefPlaceholder')}
                    disabled={pending}
                  />
                </label>
                <label className="form-group">
                  <FieldLabel>{t('ai.environment.sources.scanPath')}</FieldLabel>
                  <input
                    type="text"
                    value={scanPath}
                    onChange={(e) => setScanPath(e.target.value)}
                    placeholder={t('ai.environment.sources.scanPathPlaceholder')}
                    disabled={pending}
                  />
                </label>
              </>
            )}

            {effectiveError ? (
              <p className="field-error" role="alert">
                {effectiveError}
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
