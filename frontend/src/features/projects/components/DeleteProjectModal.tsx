import { useEffect, useState } from 'react'
import { AlertTriangle, Trash2 } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n } from '@/shared/i18n'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { ProjectDTO } from '../types'

export interface DeleteProjectModalProps {
  isOpen: boolean
  project: ProjectDTO | null
  onClose: () => void
  onSuccess: (projectId: string) => void
  api?: ProjectsApi
}

export function DeleteProjectModal({
  isOpen,
  project,
  onClose,
  onSuccess,
  api = projectsApi,
}: DeleteProjectModalProps) {
  const { t } = useI18n()
  const [isDeleting, setIsDeleting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  useEffect(() => {
    if (isOpen) {
      setErrorMessage(null)
    }
  }, [isOpen])

  if (!isOpen || !project) {
    return null
  }

  const handleDelete = async () => {
    setIsDeleting(true)
    setErrorMessage(null)
    try {
      await api.deleteProject(project.id, project.version)
      onSuccess(project.id)
      onClose()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : t('projects.delete.failed'))
    } finally {
      setIsDeleting(false)
    }
  }

  return (
    <Dialog
      className="confirm-modal-card"
      title={t('projects.delete.title')}
      headerIcon={<Trash2 size={18} color="var(--danger)" aria-hidden="true" />}
      pending={isDeleting}
      onClose={onClose}
    >
        <div className="modal-body confirm-modal-body">
          {errorMessage && (
            <div className="form-error-banner" role="alert">
              <AlertTriangle size={16} aria-hidden="true" />
              <span>{errorMessage}</span>
            </div>
          )}

          <p className="confirm-modal-description">
            {t('projects.delete.question', { title: project.title })}
          </p>
          <p style={{ fontSize: '0.8125rem', color: 'var(--fg-muted)', margin: 0 }}>
            {t('projects.delete.cascade')}
          </p>
        </div>

        <div className="modal-footer">
          <Button variant="ghost" onClick={onClose} disabled={isDeleting}>
            {t('projects.cancel')}
          </Button>
          <Button danger onClick={handleDelete} disabled={isDeleting}>
            {isDeleting ? t('projects.delete.deleting') : t('projects.delete.confirm')}
          </Button>
        </div>
    </Dialog>
  )
}
