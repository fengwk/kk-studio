import { useEffect, useState } from 'react'
import { AlertTriangle, Pencil, RefreshCw, X } from 'lucide-react'
import { isConflictError } from '@/shared/api/client'
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
  const [expectedVersion, setExpectedVersion] = useState('0')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isReloading, setIsReloading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [conflictDetail, setConflictDetail] = useState<string | null>(null)

  useEffect(() => {
    if (isOpen && issue) {
      setTitle(issue.title)
      setDescription(issue.description)
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

  // 409 发生后，重新从服务端获取最新版本号，但保留当前用户输入的所有编辑草稿
  const handleReloadVersionKeepDraft = async () => {
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

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      setErrorMessage('Issue 标题不能为空')
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    setConflictDetail(null)
    try {
      const updated = await api.updateIssue(issue.id, {
        expectedVersion,
        title: trimmedTitle,
        description: description.trim(),
      })
      onSuccess(updated)
      onClose()
    } catch (err) {
      if (isConflictError(err)) {
        // 409 保留编辑草稿
        setConflictDetail('Issue 已被其他操作更新 (409 冲突)。已保留您的编辑草稿，请刷新版本后重试。')
      } else {
        setErrorMessage(err instanceof Error ? err.message : '更新 Issue 失败')
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
            <h3>编辑 Issue #{issue.number ?? issue.id}</h3>
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
              <div
                className="form-error-banner"
                role="alert"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: '8px',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <AlertTriangle size={16} aria-hidden="true" />
                  <span>{conflictDetail}</span>
                </div>
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={() => void handleReloadVersionKeepDraft()}
                  disabled={isReloading}
                  style={{ whiteSpace: 'nowrap' }}
                >
                  <RefreshCw
                    size={14}
                    className={isReloading ? 'animate-spin' : ''}
                    aria-hidden="true"
                  />
                  <span>刷新版本</span>
                </button>
              </div>
            )}

            {errorMessage && (
              <div className="form-error-banner" role="alert">
                <AlertTriangle size={16} aria-hidden="true" />
                <span>{errorMessage}</span>
              </div>
            )}

            <div className="form-group">
              <label htmlFor="edit-issue-title" className="form-label required">
                需求标题
              </label>
              <input
                id="edit-issue-title"
                type="text"
                className="form-input"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                disabled={isSubmitting}
                autoFocus
              />
            </div>

            <div className="form-group">
              <label htmlFor="edit-issue-desc" className="form-label">
                需求描述
              </label>
              <textarea
                id="edit-issue-desc"
                className="form-textarea"
                rows={5}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                disabled={isSubmitting}
              />
            </div>

            <div style={{ fontSize: '12px', color: 'var(--fg-muted)' }}>
              <span>当前状态: <code>{issue.state}</code> · 编辑版本: <code>{expectedVersion}</code></span>
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
              disabled={isSubmitting}
            >
              {isSubmitting ? '保存中...' : '保存修改'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
