import { useEffect, useState } from 'react'
import { X, FolderPlus } from 'lucide-react'
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
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [coordinatorAgentName, setCoordinatorAgentName] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  useEffect(() => {
    if (isOpen) {
      setTitle('')
      setDescription('')
      setCoordinatorAgentName('')
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

  if (!isOpen) {
    return null
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      setErrorMessage('项目名称不能为空')
      return
    }
    const trimmedCoordinatorAgentName = coordinatorAgentName.trim()
    if (!trimmedCoordinatorAgentName) {
      setErrorMessage('Coordinator Agent 名称不能为空')
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    try {
      const created = await api.createProject({
        title: trimmedTitle,
        description: description.trim() || null,
        coordinatorAgentName: trimmedCoordinatorAgentName,
      })
      onSuccess(created)
      onClose()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '创建项目失败')
    } finally {
      setIsSubmitting(false)
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
        className="modal-card resource-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label="新建项目"
      >
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <FolderPlus size={18} aria-hidden="true" />
            <h3>新建项目</h3>
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

        <form onSubmit={handleSubmit}>
          <div className="modal-body">
            {errorMessage && (
              <div className="form-error-banner" role="alert">
                {errorMessage}
              </div>
            )}

            <div className="form-group">
              <label htmlFor="create-project-title">
                项目名称 <span style={{ color: 'var(--danger)' }}>*</span>
              </label>
              <input
                id="create-project-title"
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="输入项目标题"
                autoFocus
                required
              />
            </div>

            <div className="form-group">
              <label htmlFor="create-project-desc">项目描述</label>
              <textarea
                id="create-project-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="可选的项目背景与目标描述"
                rows={3}
              />
            </div>

            <div className="form-group">
              <label htmlFor="create-project-coordinator">
                Coordinator Agent 名称 <span style={{ color: 'var(--danger)' }}>*</span>
              </label>
              <input
                id="create-project-coordinator"
                type="text"
                value={coordinatorAgentName}
                onChange={(e) => setCoordinatorAgentName(e.target.value)}
                placeholder="输入负责编排的 Coordinator Agent 名称"
                required
              />
            </div>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="ghost-btn"
              onClick={onClose}
              disabled={isSubmitting}
            >
              取消
            </button>
            <button
              type="submit"
              className="btn-primary"
              disabled={isSubmitting || !title.trim() || !coordinatorAgentName.trim()}
            >
              {isSubmitting ? '创建中...' : '创建项目'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
