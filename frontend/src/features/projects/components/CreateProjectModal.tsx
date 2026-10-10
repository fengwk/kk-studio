import { useEffect, useState } from 'react'
import { FolderPlus } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n } from '@/shared/i18n'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { ProjectDTO } from '../types'

export interface CreateProjectModalProps {
  isOpen: boolean
  onClose: () => void
  onSuccess: (created: ProjectDTO) => void
  api?: ProjectsApi
}

export function CreateProjectModal({
  isOpen,
  onClose,
  onSuccess,
  api = projectsApi,
}: CreateProjectModalProps) {
  const { t } = useI18n()
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [yoloEnabled, setYoloEnabled] = useState(true)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  useEffect(() => {
    if (isOpen) {
      setTitle('')
      setDescription('')
      setYoloEnabled(true)
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
      setErrorMessage(t('projects.edit.nameRequired'))
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    try {
      const created = await api.createProject({
        title: trimmedTitle,
        description: description.trim() || null,
        yoloEnabled,
      })
      onSuccess(created)
      onClose()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : t('projects.create.failed'))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <Dialog
      className="resource-modal-card"
      title={t('projects.create')}
      headerIcon={<FolderPlus size={18} aria-hidden="true" />}
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

            <label className="edit-project-field" htmlFor="create-project-title">
              <FieldLabel required>{t('projects.edit.projectName')}</FieldLabel>
              <TextInput
                id="create-project-title"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder={t('projects.create.namePlaceholder')}
                disabled={isSubmitting}
                autoFocus
              />
            </label>

            <label className="edit-project-field" htmlFor="create-project-desc">
              <FieldLabel>{t('projects.edit.description')}</FieldLabel>
              <TextArea
                id="create-project-desc"
                rows={3}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={t('projects.create.descPlaceholder')}
                disabled={isSubmitting}
              />
            </label>

            <div className="edit-project-field">
              <Checkbox
                checked={yoloEnabled}
                onChange={setYoloEnabled}
                label={t('projects.edit.yoloToggle')}
                disabled={isSubmitting}
              />
            </div>
          </div>

          <div className="modal-footer">
            <Button variant="ghost" onClick={onClose} disabled={isSubmitting}>
              {t('projects.cancel')}
            </Button>
            <Button type="submit" loading={isSubmitting}>
              {t('projects.create.submit')}
            </Button>
          </div>
        </form>
    </Dialog>
  )
}
