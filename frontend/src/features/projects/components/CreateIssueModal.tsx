import { useEffect, useState } from 'react'
import { FilePlus, X } from 'lucide-react'
import { useCatalogAgentNames } from '../useCatalogAgentNames'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { IssueDTO } from '../types'

export interface CreateIssueModalProps {
  isOpen: boolean
  projectId: string
  defaultStatus?: 'BACKLOG' | 'TODO'
  onClose: () => void
  onSuccess: (created: IssueDTO) => void
  api?: ProjectsApi
}

export function CreateIssueModal({
  isOpen,
  projectId,
  defaultStatus = 'BACKLOG',
  onClose,
  onSuccess,
  api = projectsApi,
}: CreateIssueModalProps) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [assigneeAgentName, setAssigneeAgentName] = useState('')
  const [reviewerAgentName, setReviewerAgentName] = useState('')
  const [initialStatus, setInitialStatus] = useState<'BACKLOG' | 'TODO'>(defaultStatus)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const { agentOptions } = useCatalogAgentNames({ enabled: isOpen })

  useEffect(() => {
    if (isOpen) {
      setTitle('')
      setDescription('')
      setAssigneeAgentName('')
      setReviewerAgentName('')
      setInitialStatus(defaultStatus)
      setErrorMessage(null)
    }
  }, [isOpen, defaultStatus])

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
      setErrorMessage('Issue 标题不能为空')
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    try {
      const created = await api.createIssue(projectId, {
        title: trimmedTitle,
        description: description.trim() || null,
        assigneeAgentName: assigneeAgentName.trim() || null,
        reviewerAgentName: reviewerAgentName.trim() || null,
        initialStatus,
      })
      onSuccess(created)
      onClose()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '创建 Issue 失败')
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
        aria-label="新建 Issue"
      >
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <FilePlus size={18} aria-hidden="true" />
            <h3>新建 Issue</h3>
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
              <label htmlFor="create-issue-title">
                标题 <span style={{ color: 'var(--danger)' }}>*</span>
              </label>
              <input
                id="create-issue-title"
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="简明描述此 Issue 目标"
                autoFocus
                required
              />
            </div>

            <div className="form-group">
              <label htmlFor="create-issue-desc">规格与详细要求 (Spec)</label>
              <textarea
                id="create-issue-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="详细说明此任务的上下文、边界与完成判据"
                rows={4}
              />
            </div>

            <div className="form-group">
              <label htmlFor="create-issue-status">初始状态</label>
              <select
                id="create-issue-status"
                value={initialStatus}
                onChange={(e) => setInitialStatus(e.target.value as 'BACKLOG' | 'TODO')}
              >
                <option value="BACKLOG">BACKLOG (需求池)</option>
                <option value="TODO">TODO (待办，就绪可执行)</option>
              </select>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
              <div className="form-group">
                <label htmlFor="create-issue-assignee">Assignee Agent (EXECUTOR)</label>
                <select
                  id="create-issue-assignee"
                  value={assigneeAgentName}
                  onChange={(e) => setAssigneeAgentName(e.target.value)}
                  aria-label="Assignee Agent (EXECUTOR)"
                >
                  <option value="">未指定</option>
                  {agentOptions.map((name) => (
                    <option key={name} value={name}>
                      {name}
                    </option>
                  ))}
                </select>
              </div>

              <div className="form-group">
                <label htmlFor="create-issue-reviewer">Reviewer Agent (REVIEWER)</label>
                <select
                  id="create-issue-reviewer"
                  value={reviewerAgentName}
                  onChange={(e) => setReviewerAgentName(e.target.value)}
                  aria-label="Reviewer Agent (REVIEWER)"
                >
                  <option value="">人工审核</option>
                  {agentOptions.map((name) => (
                    <option key={name} value={name}>
                      {name}
                    </option>
                  ))}
                </select>
              </div>
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
              disabled={isSubmitting || !title.trim()}
            >
              {isSubmitting ? '创建中...' : '创建 Issue'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
