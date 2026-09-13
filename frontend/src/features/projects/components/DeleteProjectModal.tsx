import { useEffect, useState } from 'react'
import { AlertTriangle, Trash2, X } from 'lucide-react'
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

  useEffect(() => {
    if (!isOpen) {
      return
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, onClose])

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
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          onClose()
        }
      }}
    >
      <div
        className="modal-card confirm-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label="确认删除项目"
      >
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Trash2 size={18} color="var(--danger)" aria-hidden="true" />
            <h3>确认删除项目</h3>
          </div>
          <button
            type="button"
            className="modal-close-button"
            onClick={onClose}
            aria-label="关闭"
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

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
          <button
            type="button"
            className="ghost-btn"
            onClick={onClose}
            disabled={isDeleting}
          >
            取消
          </button>
          <button
            type="button"
            className="btn-primary danger"
            onClick={handleDelete}
            disabled={isDeleting}
          >
            {isDeleting ? '删除中...' : '确认删除'}
          </button>
        </div>
      </div>
    </div>
  )
}
