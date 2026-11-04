import { useEffect, useState } from 'react'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { useI18n } from '@/shared/i18n'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { ProjectDTO } from '@/shared/api/generated/contracts'

export interface DeleteProjectModalProps {
  isOpen: boolean
  project: ProjectDTO | null
  onClose: () => void
  onSuccess: (projectId: string) => void
  api?: ProjectsApi
}

/** 项目删除是普通确认：走共享 ConfirmActionModal，危险语义与在途门禁由它统一提供。 */
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
      setErrorMessage(err instanceof Error ? err.message : t('shared.requestFailed'))
    } finally {
      setIsDeleting(false)
    }
  }

  return (
    <ConfirmActionModal
      className="modal-touch-target"
      modal={{
        title: t('projects.delete.title'),
        description: `${t('projects.delete.question', { title: project.title })} ${t('projects.delete.cascade')}`,
        confirmLabel: t('projects.delete.confirm'),
        icon: 'delete',
        tone: 'danger',
        onConfirm: () => void handleDelete(),
      }}
      pending={isDeleting}
      error={errorMessage}
      onClose={onClose}
    />
  )
}
