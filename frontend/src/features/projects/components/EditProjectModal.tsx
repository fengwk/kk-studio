import { useEffect, useRef, useState } from 'react'
import { AlertTriangle, Code, Pencil, RefreshCw, X } from 'lucide-react'
import { isConflictError } from '@/shared/api/client'
import { Checkbox } from '@/shared/ui/console/Checkbox'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { ProjectDTO, ProjectWorkflowDTO } from '../types'
import { decodeProjectWorkflow } from '../codecs'

export interface EditProjectModalProps {
  isOpen: boolean
  project: ProjectDTO | null
  onClose: () => void
  onSuccess: (updated: ProjectDTO) => void
  api?: ProjectsApi
}

type TabKey = 'basic' | 'workflow'

export function EditProjectModal({
  isOpen,
  project,
  onClose,
  onSuccess,
  api = projectsApi,
}: EditProjectModalProps) {
  const [activeTab, setActiveTab] = useState<TabKey>('basic')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [yoloEnabled, setYoloEnabled] = useState(true)
  const [workflowJson, setWorkflowJson] = useState('')
  const [expectedVersion, setExpectedVersion] = useState('0')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isReloading, setIsReloading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [conflictDetail, setConflictDetail] = useState<string | null>(null)

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
    setWorkflowJson(JSON.stringify(project.workflow, null, 2))
    setExpectedVersion(project.version)
    setErrorMessage(null)
    setConflictDetail(null)
    setActiveTab('basic')
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

  const handleFormatJson = () => {
    try {
      const parsed = JSON.parse(workflowJson)
      setWorkflowJson(JSON.stringify(parsed, null, 2))
      setErrorMessage(null)
    } catch (err) {
      setErrorMessage(err instanceof Error ? `JSON 格式错误: ${err.message}` : 'JSON 格式不合法')
    }
  }

  // 409 发生后，重新从服务端获取最新版本号，但保留当前用户输入的所有编辑草稿
  const handleReloadVersionKeepDraft = async () => {
    setIsReloading(true)
    setErrorMessage(null)
    try {
      const fresh = await api.getProject(project.id)
      setExpectedVersion(fresh.version)
      setConflictDetail(null)
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '重新加载最新版本失败')
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

    let parsedWorkflow: ProjectWorkflowDTO
    try {
      const raw = JSON.parse(workflowJson)
      parsedWorkflow = decodeProjectWorkflow(raw)
      // 客户端语义校验：必须包含 INIT, BLOCKED, DONE
      const states = parsedWorkflow.states
      const codes = new Set(states.map((s) => s.state))
      if (!codes.has('INIT') || !codes.has('BLOCKED') || !codes.has('DONE')) {
        setErrorMessage('工作流必须包含固定的 INIT、BLOCKED 和 DONE 状态')
        return
      }
      if (codes.size !== states.length) {
        setErrorMessage('工作流状态编码不可重复')
        return
      }
    } catch (err) {
      setErrorMessage(err instanceof Error ? `工作流配置错误: ${err.message}` : '工作流 JSON 不合法')
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    setConflictDetail(null)

    let currentVer = expectedVersion
    let updatedProject: ProjectDTO = project

    try {
      // 1. 若基础配置有变更，更新配置
      if (trimmedTitle !== project.title || description.trim() !== project.description) {
        updatedProject = await api.updateProject(project.id, {
          expectedVersion: currentVer,
          title: trimmedTitle,
          description: description.trim(),
        })
        currentVer = updatedProject.version
      }

      // 2. 若 YOLO 状态变更，更新 YOLO
      if (yoloEnabled !== updatedProject.yoloEnabled) {
        updatedProject = await api.updateYolo(project.id, {
          expectedVersion: currentVer,
          yoloEnabled,
        })
        currentVer = updatedProject.version
      }

      // 3. 严格保存工作流配置
      const initialJsonNormalized = JSON.stringify(project.workflow)
      const currentJsonNormalized = JSON.stringify(parsedWorkflow)
      if (initialJsonNormalized !== currentJsonNormalized) {
        updatedProject = await api.updateWorkflow(project.id, {
          expectedVersion: currentVer,
          workflow: parsedWorkflow,
        })
      }

      onSuccess(updatedProject)
      onClose()
    } catch (err) {
      if (isConflictError(err)) {
        // 关键需求：409 保留编辑草稿，不丢失用户修改
        setConflictDetail('项目配置已在别处发生更新 (409 冲突)。已为您保留编辑草稿，请点击更新版本后重试。')
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
        style={{ maxWidth: '640px' }}
      >
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Pencil size={18} aria-hidden="true" />
            <h3>编辑项目配置</h3>
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

        <div className="tab-nav" style={{ padding: '0 20px', borderBottom: '1px solid var(--border)' }}>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'basic' ? 'active' : ''}`}
            onClick={() => setActiveTab('basic')}
          >
            基础信息
          </button>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'workflow' ? 'active' : ''}`}
            onClick={() => setActiveTab('workflow')}
          >
            工作流 JSON 配置
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

            {activeTab === 'basic' && (
              <>
                <div className="form-group">
                  <label htmlFor="edit-project-title" className="form-label required">
                    项目名称
                  </label>
                  <input
                    id="edit-project-title"
                    type="text"
                    className="form-input"
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                    disabled={isSubmitting}
                    autoFocus
                  />
                </div>

                <div className="form-group">
                  <label htmlFor="edit-project-desc" className="form-label">
                    项目描述
                  </label>
                  <textarea
                    id="edit-project-desc"
                    className="form-textarea"
                    rows={3}
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    disabled={isSubmitting}
                  />
                </div>

                <div className="form-group">
                  <Checkbox
                    checked={yoloEnabled}
                    onChange={setYoloEnabled}
                    label="启用 YOLO 执行策略 (自主执行，跳过人工交互门禁)"
                    disabled={isSubmitting}
                  />
                </div>

                <div className="form-group" style={{ fontSize: '12px', color: 'var(--fg-muted)' }}>
                  <span>期望版本: <code>{expectedVersion}</code></span>
                </div>
              </>
            )}

            {activeTab === 'workflow' && (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                  <label htmlFor="edit-project-workflow-json" className="form-label" style={{ margin: 0 }}>
                    工作流配置 (JSON 严格校验)
                  </label>
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={handleFormatJson}
                    style={{ fontSize: '12px', padding: '2px 8px' }}
                  >
                    <Code size={12} aria-hidden="true" />
                    <span>格式化 JSON</span>
                  </button>
                </div>

                <div className="form-group">
                  <textarea
                    id="edit-project-workflow-json"
                    className="form-textarea"
                    rows={12}
                    style={{ fontFamily: 'monospace', fontSize: '12px' }}
                    value={workflowJson}
                    onChange={(e) => setWorkflowJson(e.target.value)}
                    disabled={isSubmitting}
                    placeholder="输入 workflow JSON 配置..."
                  />
                </div>

                <div style={{ fontSize: '12px', color: 'var(--fg-dim)' }}>
                  <p style={{ margin: 0 }}>
                    * 必须包含固定保留状态：<code>INIT</code>、<code>BLOCKED</code> 与 <code>DONE</code>。
                    工作阶段可配置 <code>agent</code>、<code>environment</code>、<code>instructions</code>、<code>maxRuns</code> 及 <code>next</code> 白名单。
                  </p>
                </div>
              </>
            )}
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
              {isSubmitting ? '保存中...' : '保存更改'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
