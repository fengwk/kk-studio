import { useEffect, useState } from 'react'
import { X, Pencil, AlertTriangle, RefreshCw } from 'lucide-react'
import { isConflictError } from '@/shared/api/client'
import { presentConflict } from '@/shared/conflict/conflict-presenter'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { ProjectDTO } from '../types'

export interface EditProjectModalProps {
  isOpen: boolean
  project: ProjectDTO | null
  onClose: () => void
  onSuccess: (updated: ProjectDTO) => void
  api?: ProjectsApi
}

export function EditProjectModal({
  isOpen,
  project,
  onClose,
  onSuccess,
  api = projectsApi,
}: EditProjectModalProps) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [coordinatorAgentName, setCoordinatorAgentName] = useState('')
  const [expectedVersion, setExpectedVersion] = useState('0')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isReloading, setIsReloading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [conflictDetail, setConflictDetail] = useState<{
    reason: string
    detail: string
  } | null>(null)

  useEffect(() => {
    if (isOpen && project) {
      setTitle(project.title)
      setDescription(project.description)
      setCoordinatorAgentName(project.coordinatorAgentName ?? '')
      setExpectedVersion(project.version)
      setErrorMessage(null)
      setConflictDetail(null)
    }
  }, [isOpen, project])

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

  const handleReload = async () => {
    setIsReloading(true)
    setErrorMessage(null)
    try {
      const fresh = await api.getProject(project.id)
      // 更新最新版本号，清除冲突状态；保留用户当前表单草稿
      setExpectedVersion(fresh.version)
      setConflictDetail(null)
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '重新加载最新项目数据失败')
    } finally {
      setIsReloading(false)
    }
  }

  const handleDiscardAndReset = async () => {
    setIsReloading(true)
    setErrorMessage(null)
    try {
      const fresh = await api.getProject(project.id)
      setTitle(fresh.title)
      setDescription(fresh.description)
      setCoordinatorAgentName(fresh.coordinatorAgentName ?? '')
      setExpectedVersion(fresh.version)
      setConflictDetail(null)
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '重置项目数据失败')
    } finally {
      setIsReloading(false)
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      setErrorMessage('项目名称不能为空')
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    try {
      const updated = await api.updateProject(project.id, {
        expectedVersion,
        title: trimmedTitle,
        description: description.trim() || null,
        coordinatorAgentName: coordinatorAgentName.trim() || null,
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
        setErrorMessage(err instanceof Error ? err.message : '更新项目失败')
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
        aria-label="编辑项目"
      >
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Pencil size={18} aria-hidden="true" />
            <h3>编辑项目</h3>
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
                  服务端项目版本已更新，您编辑的内容已被妥善保存在当前草稿中。
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
                    放弃草稿重置为服务端内容
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
              <label htmlFor="edit-project-title">
                项目名称 <span style={{ color: 'var(--danger)' }}>*</span>
              </label>
              <input
                id="edit-project-title"
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="输入项目标题"
                autoFocus
                required
              />
            </div>

            <div className="form-group">
              <label htmlFor="edit-project-desc">项目描述</label>
              <textarea
                id="edit-project-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="可选的项目背景与目标描述"
                rows={3}
              />
            </div>

            <div className="form-group">
              <label htmlFor="edit-project-coordinator">Coordinator Agent 名称</label>
              <input
                id="edit-project-coordinator"
                type="text"
                value={coordinatorAgentName}
                onChange={(e) => setCoordinatorAgentName(e.target.value)}
                placeholder="可选，指定负责编排的 Coordinator Agent 名称"
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
