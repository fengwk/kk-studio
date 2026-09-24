import { useEffect, useRef, useState } from 'react'
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
  const [yoloEnabled, setYoloEnabled] = useState(true)
  const [maxReviewRejections, setMaxReviewRejections] = useState<number | ''>(3)
  const [expectedVersion, setExpectedVersion] = useState('0')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isReloading, setIsReloading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [conflictDetail, setConflictDetail] = useState<{
    reason: string
    detail: string
  } | null>(null)
  const initializedProjectIdRef = useRef<string | null>(null)

  useEffect(() => {
    if (!isOpen || !project) {
      initializedProjectIdRef.current = null
      return
    }
    if (initializedProjectIdRef.current === project.id) {
      return
    }
    initializedProjectIdRef.current = project.id
    setTitle(project.title)
    setDescription(project.description)
    setYoloEnabled(project.yoloEnabled)
    setMaxReviewRejections(parseInt(project.maxReviewRejections, 10) || 3)
    setExpectedVersion(project.version)
    setErrorMessage(null)
    setConflictDetail(null)
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
      setYoloEnabled(fresh.yoloEnabled)
      setMaxReviewRejections(parseInt(fresh.maxReviewRejections, 10) || 3)
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
    const rejections = typeof maxReviewRejections === 'number' ? maxReviewRejections : 3
    if (rejections <= 0) {
      setErrorMessage('最大打回次数必须为正整数')
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    try {
      const updated = await api.updateProject(project.id, {
        expectedVersion,
        title: trimmedTitle,
        description: description.trim() || null,
        yoloEnabled,
        maxReviewRejections: rejections,
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
                    保留草稿并重新加载最新版本号
                  </button>
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={handleDiscardAndReset}
                    disabled={isReloading}
                  >
                    放弃更改并完全刷新
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
              <label htmlFor="edit-project-max-rejections">
                最大审查打回次数 <span style={{ color: 'var(--danger)' }}>*</span>
              </label>
              <input
                id="edit-project-max-rejections"
                type="number"
                min={1}
                value={maxReviewRejections}
                onChange={(e) => {
                  const val = e.target.value
                  setMaxReviewRejections(val === '' ? '' : parseInt(val, 10))
                }}
                required
                aria-label="最大审查打回次数"
              />
              <span className="field-hint" style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '4px', display: 'block' }}>
                连续打回达到该次数后单据转入 BLOCKED 状态
              </span>
            </div>

            <div className="form-group" style={{ display: 'flex', alignItems: 'flex-start', gap: '8px' }}>
              <input
                id="edit-project-yolo"
                type="checkbox"
                checked={yoloEnabled}
                onChange={(e) => setYoloEnabled(e.target.checked)}
                style={{ marginTop: '3px' }}
                aria-label="YOLO 模式"
              />
              <div>
                <label htmlFor="edit-project-yolo" style={{ cursor: 'pointer', fontWeight: 500 }}>
                  YOLO 模式 (自动执行)
                </label>
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)', display: 'block' }}>
                  新建 Issue 时自动启动 Agent Run 分支，无需手动触发
                </span>
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
              disabled={
                isSubmitting ||
                !title.trim() ||
                (typeof maxReviewRejections === 'number' && maxReviewRejections <= 0)
              }
            >
              {isSubmitting ? '保存中...' : '保存更改'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
