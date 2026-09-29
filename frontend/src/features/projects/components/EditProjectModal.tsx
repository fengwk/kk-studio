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
  const [currentVersion, setCurrentVersion] = useState('0')

  const [isReloading, setIsReloading] = useState(false)

  // 基础信息独立状态
  const [isSubmittingBasic, setIsSubmittingBasic] = useState(false)
  const [basicErrorMessage, setBasicErrorMessage] = useState<string | null>(null)
  const [basicConflictDetail, setBasicConflictDetail] = useState<string | null>(null)
  const [basicSuccessMessage, setBasicSuccessMessage] = useState<string | null>(null)

  // YOLO 独立状态
  const [isSubmittingYolo, setIsSubmittingYolo] = useState(false)
  const [yoloErrorMessage, setYoloErrorMessage] = useState<string | null>(null)
  const [yoloConflictDetail, setYoloConflictDetail] = useState<string | null>(null)
  const [yoloSuccessMessage, setYoloSuccessMessage] = useState<string | null>(null)

  // Workflow 独立状态
  const [isSubmittingWorkflow, setIsSubmittingWorkflow] = useState(false)
  const [workflowErrorMessage, setWorkflowErrorMessage] = useState<string | null>(null)
  const [workflowConflictDetail, setWorkflowConflictDetail] = useState<string | null>(null)
  const [workflowSuccessMessage, setWorkflowSuccessMessage] = useState<string | null>(null)

  const initializedProjectIdRef = useRef<string | null>(null)
  const lastSyncedProjectRef = useRef<ProjectDTO | null>(null)

  useEffect(() => {
    if (!isOpen || !project) {
      initializedProjectIdRef.current = null
      lastSyncedProjectRef.current = null
      return
    }

    if (initializedProjectIdRef.current !== project.id) {
      // 首次载入该项目：全新初始化
      initializedProjectIdRef.current = project.id
      lastSyncedProjectRef.current = project
      setTitle(project.title)
      setDescription(project.description)
      setYoloEnabled(project.yoloEnabled)
      setWorkflowJson(JSON.stringify(project.workflow, null, 2))
      setCurrentVersion(project.version)

      setBasicErrorMessage(null)
      setBasicConflictDetail(null)
      setBasicSuccessMessage(null)

      setYoloErrorMessage(null)
      setYoloConflictDetail(null)
      setYoloSuccessMessage(null)

      setWorkflowErrorMessage(null)
      setWorkflowConflictDetail(null)
      setWorkflowSuccessMessage(null)

      setActiveTab('basic')
      return
    }

    // 同一项目的后续 prop 变动（例如父组件根据 onSuccess 或外部刷新传回更新后的 project）
    // 权威版本无条件对齐最新
    setCurrentVersion(project.version)

    const last = lastSyncedProjectRef.current
    if (last) {
      // 只有在字段未被用户编辑（clean）时才更新对应输入；若已被用户修改为草稿，严格保留草稿不予覆盖！
      setTitle((prev) => (prev === last.title ? project.title : prev))
      setDescription((prev) => (prev === last.description ? project.description : prev))
      setYoloEnabled((prev) => (prev === last.yoloEnabled ? project.yoloEnabled : prev))
      const lastWorkflowStr = JSON.stringify(last.workflow, null, 2)
      setWorkflowJson((prev) => (prev === lastWorkflowStr ? JSON.stringify(project.workflow, null, 2) : prev))
    }
    lastSyncedProjectRef.current = project
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
      setWorkflowErrorMessage(null)
    } catch (err) {
      setWorkflowErrorMessage(err instanceof Error ? `JSON 格式错误: ${err.message}` : 'JSON 格式不合法')
    }
  }

  // 刷新版本号：从服务端获取最新版本号更新权威基准，完整保留用户在各个表单中已编辑的所有草稿
  const handleReloadVersionKeepDraft = async () => {
    setIsReloading(true)
    setBasicErrorMessage(null)
    setBasicConflictDetail(null)
    setYoloErrorMessage(null)
    setYoloConflictDetail(null)
    setWorkflowErrorMessage(null)
    setWorkflowConflictDetail(null)
    try {
      const fresh = await api.getProject(project.id)
      setCurrentVersion(fresh.version)
    } catch (err) {
      setBasicErrorMessage(err instanceof Error ? err.message : '重新加载最新版本失败')
    } finally {
      setIsReloading(false)
    }
  }

  // 独立保存基础信息（名称 + 描述），CAS 成功立即更新权威版本
  const handleSaveBasic = async (e: React.FormEvent) => {
    e.preventDefault()
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      setBasicErrorMessage('项目名称不能为空')
      return
    }

    setIsSubmittingBasic(true)
    setBasicErrorMessage(null)
    setBasicConflictDetail(null)
    setBasicSuccessMessage(null)

    try {
      const updated = await api.updateProject(project.id, {
        expectedVersion: currentVersion,
        title: trimmedTitle,
        description: description.trim(),
      })
      setCurrentVersion(updated.version)
      setBasicSuccessMessage('基础信息保存成功')
      onSuccess(updated)
    } catch (err) {
      if (isConflictError(err)) {
        setBasicConflictDetail('项目配置已在别处发生更新 (409 冲突)。已为您保留编辑草稿，请点击刷新版本后重试。')
      } else {
        setBasicErrorMessage(err instanceof Error ? err.message : '更新基础信息失败')
      }
    } finally {
      setIsSubmittingBasic(false)
    }
  }

  // 独立保存 YOLO 模式，CAS 成功立即更新权威版本
  const handleSaveYolo = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsSubmittingYolo(true)
    setYoloErrorMessage(null)
    setYoloConflictDetail(null)
    setYoloSuccessMessage(null)

    try {
      const updated = await api.updateYolo(project.id, {
        expectedVersion: currentVersion,
        yoloEnabled,
      })
      setCurrentVersion(updated.version)
      setYoloSuccessMessage('YOLO 模式保存成功')
      onSuccess(updated)
    } catch (err) {
      if (isConflictError(err)) {
        setYoloConflictDetail('YOLO 模式更新冲突 (409)。已为您保留设置，请点击刷新版本后重试。')
      } else {
        setYoloErrorMessage(err instanceof Error ? err.message : '更新 YOLO 模式失败')
      }
    } finally {
      setIsSubmittingYolo(false)
    }
  }

  // 独立保存工作流 JSON，CAS 成功立即更新权威版本
  const handleSaveWorkflow = async (e: React.FormEvent) => {
    e.preventDefault()
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

    setIsSubmittingWorkflow(true)
    setWorkflowErrorMessage(null)
    setWorkflowConflictDetail(null)
    setWorkflowSuccessMessage(null)

    try {
      const updated = await api.updateWorkflow(project.id, {
        expectedVersion: currentVersion,
        workflow: parsedWorkflow,
      })
      setCurrentVersion(updated.version)
      setWorkflowSuccessMessage('工作流配置保存成功')
      onSuccess(updated)
    } catch (err) {
      if (isConflictError(err)) {
        setWorkflowConflictDetail('工作流配置更新冲突 (409)。已为您保留编辑草稿，请点击刷新版本后重试。')
      } else {
        setWorkflowErrorMessage(err instanceof Error ? err.message : '更新工作流失败')
      }
    } finally {
      setIsSubmittingWorkflow(false)
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

        {/* 顶部权威版本号基线与刷新按钮 */}
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
            disabled={isReloading}
            style={{ fontSize: '12px', padding: '2px 8px' }}
          >
            <RefreshCw
              size={12}
              className={isReloading ? 'animate-spin' : ''}
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
              {/* 表单 1：独立保存基础信息 */}
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
                    disabled={isSubmittingBasic}
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
                    disabled={isSubmittingBasic}
                  />
                </div>

                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '12px' }}>
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={isSubmittingBasic}
                  >
                    {isSubmittingBasic ? '保存中...' : '保存基础信息'}
                  </button>
                </div>
              </form>

              {/* 表单 2：独立保存 YOLO 模式 */}
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
                    disabled={isSubmittingYolo}
                  />
                </div>

                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={isSubmittingYolo}
                  >
                    {isSubmittingYolo ? '保存中...' : '保存 YOLO 模式'}
                  </button>
                </div>
              </form>
            </div>
          )}

          {activeTab === 'workflow' && (
            /* 表单 3：独立保存工作流 JSON */
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
                  disabled={isSubmittingWorkflow}
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
                  disabled={isSubmittingWorkflow}
                >
                  {isSubmittingWorkflow ? '保存中...' : '保存工作流'}
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
