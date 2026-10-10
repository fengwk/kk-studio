import { useEffect, useState } from 'react'
import { FilePlus } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n } from '@/shared/i18n'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { IssueDTO } from '../types'

export interface CreateIssueModalProps {
  isOpen: boolean
  projectId: string
  onClose: () => void
  onSuccess: (created: IssueDTO) => void
  api?: ProjectsApi
}

export function CreateIssueModal({
  isOpen,
  projectId,
  onClose,
  onSuccess,
  api = projectsApi,
}: CreateIssueModalProps) {
  const { t } = useI18n()
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  useEffect(() => {
    if (isOpen) {
      setTitle('')
      setDescription('')
      setErrorMessage(null)
    }
  }, [isOpen])

  if (!isOpen) {
    return null
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      setErrorMessage(t('projects.issue.titleRequired'))
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    try {
      const created = await api.createIssue(projectId, {
        title: trimmedTitle,
        description: description.trim() || null,
      })
      onSuccess(created)
      onClose()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : t('projects.issue.createFailed'))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <Dialog
      className="resource-modal-card"
      title={t('projects.issue.create')}
      headerIcon={<FilePlus size={18} aria-hidden="true" />}
      pending={isSubmitting}
      onClose={onClose}
    >
        <form className="modal-card-form" onSubmit={handleSubmit}>
          <div className="modal-body">
            {errorMessage && (
              <div className="form-error-banner" role="alert">
                <span>{errorMessage}</span>
              </div>
            )}

            <label className="edit-project-field" htmlFor="create-issue-title">
              <FieldLabel required>{t('projects.issue.titleLabel')}</FieldLabel>
              <TextInput
                id="create-issue-title"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder={t('projects.issue.titlePlaceholder')}
                disabled={isSubmitting}
                autoFocus
              />
            </label>

            <label className="edit-project-field" htmlFor="create-issue-desc">
              <FieldLabel>{t('projects.issue.descLabel')}</FieldLabel>
              <TextArea
                id="create-issue-desc"
                rows={4}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={t('projects.issue.descPlaceholder')}
                disabled={isSubmitting}
              />
            </label>

            <div style={{ fontSize: '12px', color: 'var(--fg-dim)' }}>
              <span>{t('projects.issue.createHint')}</span>
            </div>
          </div>

          <div className="modal-footer">
            <Button variant="ghost" onClick={onClose} disabled={isSubmitting}>
              {t('projects.cancel')}
            </Button>
            <Button type="submit" loading={isSubmitting}>
              {t('projects.issue.createSubmit')}
            </Button>
          </div>
        </form>
    </Dialog>
  )
}
