import { useEffect, useRef, useState } from 'react'
import { AlertTriangle, CheckCircle2, Code, Pencil, RefreshCw, X } from 'lucide-react'
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
type ActiveSubmission = 'basic' | 'yolo' | 'workflow' | 'reload' | null

function compareVersions(a: string, b: string): number {
  try {
    const ba = BigInt(a)
    const bb = BigInt(b)
    return ba < bb ? -1 : ba > bb ? 1 : 0
  } catch {
    return 0
  }
}

export function EditProjectModal({
  isOpen,
  project,
  onClose,
  onSuccess,
  api = projectsApi,
}: EditProjectModalProps) {
  if (!isOpen || !project) {
    return null
  }

  return (
    <EditProjectModalContent
      key={project.id}
      project={project}
      onClose={onClose}
      onSuccess={onSuccess}
      api={api}
    />
  )
}

interface EditProjectModalContentProps {
  project: ProjectDTO
  onClose: () => void
  onSuccess: (updated: ProjectDTO) => void
  api: ProjectsApi
}

function EditProjectModalContent({
  project,
  onClose,
  onSuccess,
  api,
}: EditProjectModalContentProps) {
  const [activeTab, setActiveTab] = useState<TabKey>('basic')
  const [title, setTitle] = useState(project.title)
  const [description, setDescription] = useState(project.description)
  const [yoloEnabled, setYoloEnabled] = useState(project.yoloEnabled)
  const [workflowJson, setWorkflowJson] = useState(() => JSON.stringify(project.workflow, null, 2))
  const [currentVersion, setCurrentVersion] = useState(project.version)

  const [activeSubmission, setActiveSubmission] = useState<ActiveSubmission>(null)
  const activeSubmissionRef = useRef<ActiveSubmission>(null)

  const [basicErrorMessage, setBasicErrorMessage] = useState<string | null>(null)
  const [basicConflictDetail, setBasicConflictDetail] = useState<string | null>(null)
  const [basicSuccessMessage, setBasicSuccessMessage] = useState<string | null>(null)

  const [yoloErrorMessage, setYoloErrorMessage] = useState<string | null>(null)
  const [yoloConflictDetail, setYoloConflictDetail] = useState<string | null>(null)
  const [yoloSuccessMessage, setYoloSuccessMessage] = useState<string | null>(null)

  const [workflowErrorMessage, setWorkflowErrorMessage] = useState<string | null>(null)
  const [workflowConflictDetail, setWorkflowConflictDetail] = useState<string | null>(null)
  const [workflowSuccessMessage, setWorkflowSuccessMessage] = useState<string | null>(null)

  const isMountedRef = useRef(true)
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
    }
  }, [])

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  const advanceVersion = (newVersion: string) => {
    setCurrentVersion((prev) => (compareVersions(newVersion, prev) > 0 ? newVersion : prev))
  }

  const handleFormatJson = () => {
    try {
      const parsed = JSON.parse(workflowJson)
      setWorkflowJson(JSON.stringify(parsed, null, 2))
      setWorkflowErrorMessage(null)
    } catch (err) {
      setWorkflowErrorMessage(err instanceof Error ? `JSON 格式错误: ${err.message}` : 'JSON 格式不合法')
    }
  }

  const handleReloadVersionKeepDraft = async () => {
    if (activeSubmissionRef.current !== null) {
      return
    }
    activeSubmissionRef.current = 'reload'
    setActiveSubmission('reload')

    setBasicErrorMessage(null)
    setBasicConflictDetail(null)
    setYoloErrorMessage(null)
    setYoloConflictDetail(null)
    setWorkflowErrorMessage(null)
    setWorkflowConflictDetail(null)

    try {
      const fresh = await api.getProject(project.id)
      if (!isMountedRef.current) return
      advanceVersion(fresh.version)
    } catch (err) {
      if (!isMountedRef.current) return
      setBasicErrorMessage(err instanceof Error ? err.message : '重新加载最新版本失败')
    } finally {
      if (isMountedRef.current) {
        activeSubmissionRef.current = null
        setActiveSubmission(null)
      }
    }
  }

  const handleSaveBasic = async (e: React.FormEvent) => {
    e.preventDefault()
    if (activeSubmissionRef.current !== null) {
      return
    }

    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      setBasicErrorMessage('项目名称不能为空')
      return
    }

    activeSubmissionRef.current = 'basic'
    setActiveSubmission('basic')

    setBasicErrorMessage(null)
    setBasicConflictDetail(null)
    setBasicSuccessMessage(null)

    try {
      const updated = await api.updateProject(project.id, {
        expectedVersion: currentVersion,
        title: trimmedTitle,
        description: description.trim(),
      })
      if (!isMountedRef.current) return
      advanceVersion(updated.version)
      setBasicSuccessMessage('基础信息保存成功')
      onSuccess(updated)
    } catch (err) {
      if (!isMountedRef.current) return
      if (isConflictError(err)) {
        setBasicConflictDetail('项目配置已在别处发生更新 (409 冲突)。已为您保留编辑草稿，请点击刷新版本后重试。')
      } else {
        setBasicErrorMessage(err instanceof Error ? err.message : '更新基础信息失败')
      }
    } finally {
      if (isMountedRef.current) {
        activeSubmissionRef.current = null
        setActiveSubmission(null)
      }
    }
  }

  const handleSaveYolo = async (e: React.FormEvent) => {
    e.preventDefault()
    if (activeSubmissionRef.current !== null) {
      return
    }

    activeSubmissionRef.current = 'yolo'
    setActiveSubmission('yolo')

    setYoloErrorMessage(null)
    setYoloConflictDetail(null)
    setYoloSuccessMessage(null)

    try {
      const updated = await api.updateYolo(project.id, {
        expectedVersion: currentVersion,
        yoloEnabled,
      })
      if (!isMountedRef.current) return
      advanceVersion(updated.version)
      setYoloSuccessMessage('YOLO 模式保存成功')
      onSuccess(updated)
    } catch (err) {
      if (!isMountedRef.current) return
      if (isConflictError(err)) {
        setYoloConflictDetail('YOLO 模式更新冲突 (409)。已为您保留设置，请点击刷新版本后重试。')
      } else {
        setYoloErrorMessage(err instanceof Error ? err.message : '更新 YOLO 模式失败')
      }
    } finally {
      if (isMountedRef.current) {
        activeSubmissionRef.current = null
        setActiveSubmission(null)
      }
    }
  }

  const handleSaveWorkflow = async (e: React.FormEvent) => {
    e.preventDefault()
    if (activeSubmissionRef.current !== null) {
      return
    }

    let parsedWorkflow: ProjectWorkflowDTO
    try {
      const raw = JSON.parse(workflowJson)
      parsedWorkflow = decodeProjectWorkflow(raw)
      const states = parsedWorkflow.states
      const codes = new Set(states.map((s) => s.state))
      if (!codes.has('INIT') || !codes.has('BLOCKED') || !codes.has('DONE')) {
        setWorkflowErrorMessage('工作流必须包含固定的 INIT、BLOCKED 和 DONE 状态')
        return
      }
      if (codes.size !== states.length) {
        setWorkflowErrorMessage('工作流状态编码不可重复')
        return
      }
    } catch (err) {
      setWorkflowErrorMessage(err instanceof Error ? `工作流配置错误: ${err.message}` : '工作流 JSON 不合法')
      return
    }

    activeSubmissionRef.current = 'workflow'
    setActiveSubmission('workflow')

    setWorkflowErrorMessage(null)
    setWorkflowConflictDetail(null)
    setWorkflowSuccessMessage(null)

    try {
      const updated = await api.updateWorkflow(project.id, {
        expectedVersion: currentVersion,
        workflow: parsedWorkflow,
      })
      if (!isMountedRef.current) return
      advanceVersion(updated.version)
      setWorkflowSuccessMessage('工作流配置保存成功')
      onSuccess(updated)
    } catch (err) {
      if (!isMountedRef.current) return
      if (isConflictError(err)) {
        setWorkflowConflictDetail('工作流配置更新冲突 (409)。已为您保留编辑草稿，请点击刷新版本后重试。')
      } else {
        setWorkflowErrorMessage(err instanceof Error ? err.message : '更新工作流失败')
      }
    } finally {
      if (isMountedRef.current) {
        activeSubmissionRef.current = null
        setActiveSubmission(null)
      }
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

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '8px 20px',
            backgroundColor: 'var(--bg-subtle)',
            borderBottom: '1px solid var(--border)',
            fontSize: '12px',
          }}
        >
          <span style={{ color: 'var(--fg-muted)' }}>
            期望版本: <code>{currentVersion}</code>
          </span>
          <button
            type="button"
            className="ghost-btn"
            onClick={() => void handleReloadVersionKeepDraft()}
            disabled={activeSubmission !== null}
            style={{ fontSize: '12px', padding: '2px 8px' }}
          >
            <RefreshCw
              size={12}
              className={activeSubmission === 'reload' ? 'animate-spin' : ''}
              aria-hidden="true"
            />
            <span>刷新版本</span>
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

        <div className="modal-body" style={{ maxHeight: 'calc(80vh - 170px)', overflowY: 'auto' }}>
          {activeTab === 'basic' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
              <form
                onSubmit={handleSaveBasic}
                style={{
                  padding: '16px',
                  borderRadius: '6px',
                  border: '1px solid var(--border)',
                  backgroundColor: 'var(--bg-subtle)',
                }}
              >
                <h4 style={{ margin: '0 0 12px 0', fontSize: '14px', fontWeight: 600 }}>基础信息</h4>

                {basicConflictDetail && (
                  <div className="form-error-banner" role="alert" style={{ marginBottom: '12px' }}>
                    <AlertTriangle size={16} aria-hidden="true" />
                    <span>{basicConflictDetail}</span>
                  </div>
                )}

                {basicErrorMessage && (
                  <div className="form-error-banner" role="alert" style={{ marginBottom: '12px' }}>
                    <AlertTriangle size={16} aria-hidden="true" />
                    <span>{basicErrorMessage}</span>
                  </div>
                )}

                {basicSuccessMessage && (
                  <div
                    className="form-success-banner"
                    role="status"
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: '8px',
                      marginBottom: '12px',
                      color: 'var(--success)',
                      fontSize: '13px',
                    }}
                  >
                    <CheckCircle2 size={16} aria-hidden="true" />
                    <span>{basicSuccessMessage}</span>
                  </div>
                )}

                <div className="form-group">
                  <label htmlFor="edit-project-title" className="form-label required">
                    项目名称
                  </label>
                  <input
                    id="edit-project-title"
                    type="text"
                    className="form-input"
                    value={title}
                    onChange={(e) => {
                      setTitle(e.target.value)
                      setBasicSuccessMessage(null)
                    }}
                    disabled={activeSubmission !== null}
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
                    onChange={(e) => {
                      setDescription(e.target.value)
                      setBasicSuccessMessage(null)
                    }}
                    disabled={activeSubmission !== null}
                  />
                </div>

                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '12px' }}>
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={activeSubmission !== null}
                  >
                    {activeSubmission === 'basic' ? '保存中...' : '保存基础信息'}
                  </button>
                </div>
              </form>

              <form
                onSubmit={handleSaveYolo}
                style={{
                  padding: '16px',
                  borderRadius: '6px',
                  border: '1px solid var(--border)',
                  backgroundColor: 'var(--bg-subtle)',
                }}
              >
                <h4 style={{ margin: '0 0 12px 0', fontSize: '14px', fontWeight: 600 }}>YOLO 执行模式</h4>

                {yoloConflictDetail && (
                  <div className="form-error-banner" role="alert" style={{ marginBottom: '12px' }}>
                    <AlertTriangle size={16} aria-hidden="true" />
                    <span>{yoloConflictDetail}</span>
                  </div>
                )}

                {yoloErrorMessage && (
                  <div className="form-error-banner" role="alert" style={{ marginBottom: '12px' }}>
                    <AlertTriangle size={16} aria-hidden="true" />
                    <span>{yoloErrorMessage}</span>
                  </div>
                )}

                {yoloSuccessMessage && (
                  <div
                    className="form-success-banner"
                    role="status"
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: '8px',
                      marginBottom: '12px',
                      color: 'var(--success)',
                      fontSize: '13px',
                    }}
                  >
                    <CheckCircle2 size={16} aria-hidden="true" />
                    <span>{yoloSuccessMessage}</span>
                  </div>
                )}

                <div className="form-group" style={{ marginBottom: '12px' }}>
                  <Checkbox
                    checked={yoloEnabled}
                    onChange={(val) => {
                      setYoloEnabled(val)
                      setYoloSuccessMessage(null)
                    }}
                    label="启用 YOLO 执行策略 (自主执行，跳过人工交互门禁)"
                    disabled={activeSubmission !== null}
                  />
                </div>

                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={activeSubmission !== null}
                  >
                    {activeSubmission === 'yolo' ? '保存中...' : '保存 YOLO 模式'}
                  </button>
                </div>
              </form>
            </div>
          )}

          {activeTab === 'workflow' && (
            <form onSubmit={handleSaveWorkflow}>
              {workflowConflictDetail && (
                <div className="form-error-banner" role="alert" style={{ marginBottom: '12px' }}>
                  <AlertTriangle size={16} aria-hidden="true" />
                  <span>{workflowConflictDetail}</span>
                </div>
              )}

              {workflowErrorMessage && (
                <div className="form-error-banner" role="alert" style={{ marginBottom: '12px' }}>
                  <AlertTriangle size={16} aria-hidden="true" />
                  <span>{workflowErrorMessage}</span>
                </div>
              )}

              {workflowSuccessMessage && (
                <div
                  className="form-success-banner"
                  role="status"
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '8px',
                    marginBottom: '12px',
                    color: 'var(--success)',
                    fontSize: '13px',
                  }}
                >
                  <CheckCircle2 size={16} aria-hidden="true" />
                  <span>{workflowSuccessMessage}</span>
                </div>
              )}

              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                <label htmlFor="edit-project-workflow-json" className="form-label" style={{ margin: 0 }}>
                  工作流配置 (JSON 严格校验)
                </label>
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={handleFormatJson}
                  disabled={activeSubmission !== null}
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
                  onChange={(e) => {
                    setWorkflowJson(e.target.value)
                    setWorkflowSuccessMessage(null)
                  }}
                  disabled={activeSubmission !== null}
                  placeholder="输入 workflow JSON 配置..."
                />
              </div>

              <div style={{ fontSize: '12px', color: 'var(--fg-dim)', marginBottom: '16px' }}>
                <p style={{ margin: 0 }}>
                  * 必须包含固定保留状态：<code>INIT</code>、<code>BLOCKED</code> 与 <code>DONE</code>。
                  工作阶段可配置 <code>agent</code>、<code>environment</code>、<code>instructions</code>、<code>maxRuns</code> 及 <code>next</code> 白名单。
                </p>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={activeSubmission !== null}
                >
                  {activeSubmission === 'workflow' ? '保存中...' : '保存工作流'}
                </button>
              </div>
            </form>
          )}
        </div>

        <div className="modal-footer">
          <button
            type="button"
            className="ghost-btn"
            onClick={onClose}
          >
            关闭
          </button>
        </div>
      </div>
    </div>
  )
}
