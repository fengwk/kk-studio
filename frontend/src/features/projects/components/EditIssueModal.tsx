import { useEffect, useState } from 'react'
import { AlertTriangle, Pencil, RefreshCw, X } from 'lucide-react'
import { isConflictError } from '@/shared/api/client'
import { presentConflict } from '@/shared/conflict/conflict-presenter'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { IssueDTO } from '../types'

export interface EditIssueModalProps {
  isOpen: boolean
  issue: IssueDTO | null
  onClose: () => void
  onSuccess: (updated: IssueDTO) => void
  api?: ProjectsApi
}

export function EditIssueModal({
  isOpen,
  issue,
  onClose,
  onSuccess,
  api = projectsApi,
}: EditIssueModalProps) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [assigneeAgentName, setAssigneeAgentName] = useState('')
  const [reviewerAgentName, setReviewerAgentName] = useState('')
  const [expectedVersion, setExpectedVersion] = useState('0')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isReloading, setIsReloading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [conflictDetail, setConflictDetail] = useState<{
    reason: string
    detail: string
  } | null>(null)

  useEffect(() => {
    if (isOpen && issue) {
      setTitle(issue.title)
      setDescription(issue.description)
      setAssigneeAgentName(issue.assigneeAgentName ?? '')
      setReviewerAgentName(issue.reviewerAgentName ?? '')
      setExpectedVersion(issue.version)
      setErrorMessage(null)
      setConflictDetail(null)
    }
  }, [isOpen, issue])

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

  if (!isOpen || !issue) {
    return null
  }

  const handleReload = async () => {
    setIsReloading(true)
    setErrorMessage(null)
    try {
      const freshDetail = await api.getIssue(issue.id)
      setExpectedVersion(freshDetail.issue.version)
      setConflictDetail(null)
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '重新加载最新 Issue 失败')
    } finally {
      setIsReloading(false)
    }
  }

  const handleDiscardAndReset = async () => {
    setIsReloading(true)
    setErrorMessage(null)
    try {
      const freshDetail = await api.getIssue(issue.id)
      setTitle(freshDetail.issue.title)
      setDescription(freshDetail.issue.description)
      setAssigneeAgentName(freshDetail.issue.assigneeAgentName ?? '')
      setReviewerAgentName(freshDetail.issue.reviewerAgentName ?? '')
      setExpectedVersion(freshDetail.issue.version)
      setConflictDetail(null)
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '重置 Issue 数据失败')
    } finally {
      setIsReloading(false)
    }
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
      const updated = await api.updateIssue(issue.id, {
        expectedVersion,
        title: trimmedTitle,
        description: description.trim() || null,
        assigneeAgentName: assigneeAgentName.trim() || null,
        reviewerAgentName: reviewerAgentName.trim() || null,
      })
      onSuccess(updated)
      onClose()
    } catch (err) {
      if (isConflictError(err)) {
        const presentation = presentConflict(err)
        setConflictDetail({
          reason: presentation?.reason || 'PROJECT_VERSION_CONFLICT',
          detail: presentation?.detail || '版本已过时。请点击重新加载获取最新版本并重试。',
        })
      } else {
        setErrorMessage(err instanceof Error ? err.message : '修改 Issue 失败')
      }
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
        aria-label="编辑 Issue"
      >
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Pencil size={18} aria-hidden="true" />
            <h3>编辑 Issue #{issue.number}</h3>
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
            {conflictDetail && (
              <div className="cas-conflict-banner" role="alert">
                <div className="cas-conflict-title">
                  <AlertTriangle size={16} aria-hidden="true" />
                  <span>版本冲突 ({conflictDetail.reason})</span>
                </div>
                <div className="cas-conflict-text">
                  服务端 Issue 版本已更新，您的草稿已保留。可点击重新加载获取最新版本号再保存。
                </div>
                <div className="cas-conflict-actions">
                  <button
                    type="button"
                    className="btn-primary"
                    onClick={handleReload}
                    disabled={isReloading}
                  >
                    <RefreshCw size={14} aria-hidden="true" />
                    {isReloading ? '同步中...' : '同步最新版本号并重试'}
                  </button>
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={handleDiscardAndReset}
                    disabled={isReloading}
                  >
                    放弃草稿重置
                  </button>
                </div>
              </div>
            )}

            {errorMessage && (
              <div className="form-error-banner" role="alert">
                {errorMessage}
              </div>
            )}

            <div className="form-group">
              <label htmlFor="edit-issue-title">
                标题 <span style={{ color: 'var(--danger)' }}>*</span>
              </label>
              <input
                id="edit-issue-title"
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="简明描述此 Issue 目标"
                autoFocus
                required
              />
            </div>

            <div className="form-group">
              <label htmlFor="edit-issue-desc">规格与详细要求 (Spec)</label>
              <textarea
                id="edit-issue-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="详细说明此任务的上下文、边界与完成判据"
                rows={4}
              />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
              <div className="form-group">
                <label htmlFor="edit-issue-assignee">Assignee Agent</label>
                <input
                  id="edit-issue-assignee"
                  type="text"
                  value={assigneeAgentName}
                  onChange={(e) => setAssigneeAgentName(e.target.value)}
                  placeholder="可选，执行者 Agent"
                />
              </div>

              <div className="form-group">
                <label htmlFor="edit-issue-reviewer">Reviewer Agent</label>
                <input
                  id="edit-issue-reviewer"
                  type="text"
                  value={reviewerAgentName}
                  onChange={(e) => setReviewerAgentName(e.target.value)}
                  placeholder="留空表示人工 Review"
                />
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
              {isSubmitting ? '保存中...' : '保存修改'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
