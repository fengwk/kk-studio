import { useEffect, useState } from 'react'
import { AlertTriangle, Trash2 } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
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
      setErrorMessage(err instanceof Error ? err.message : '删除项目失败')
    } finally {
      setIsDeleting(false)
    }
  }

  return (
    <Dialog
      className="confirm-modal-card"
      title="确认删除项目"
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
            确定要删除项目 <strong>{project.title}</strong> 吗？
          </p>
          <p style={{ fontSize: '0.8125rem', color: 'var(--fg-muted)', margin: 0 }}>
            此操作将彻底级联清理该项目及其所属 Issue、依赖和历史会话。若有活跃或未收敛的
            Run，删除将被拒绝。
          </p>
        </div>

        <div className="modal-footer">
          <Button variant="ghost" onClick={onClose} disabled={isDeleting}>
            取消
          </Button>
          <Button danger onClick={handleDelete} disabled={isDeleting}>
            {isDeleting ? '删除中...' : '确认删除'}
          </Button>
        </div>
    </Dialog>
  )
}
