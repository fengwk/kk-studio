import { useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import {
  AlertTriangle,
  Archive,
  ArrowLeft,
  Bot,
  Calendar,
  FileText,
  Layers,
  Pencil,
  RefreshCw,
  Trash2,
  X,
} from 'lucide-react'
import { CreateIssueModal } from './components/CreateIssueModal'
import { DeleteProjectModal } from './components/DeleteProjectModal'
import { EditProjectModal } from './components/EditProjectModal'
import { IssueBoard } from './components/IssueBoard'
import { IssueDetailModal } from './components/IssueDetailModal'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import type { ProjectsApi } from './projects-api'
import { projectsApi } from './projects-api'
import { createUuid } from '@/shared/lib/uuid'
import { queryKeys } from '@/shared/lib/query-keys'
import {
  clearPendingAction,
  isNetworkUnknownError,
  isPayloadEqual,
  loadPendingAction,
  storePendingAction,
  type PendingIssueAction,
} from './pending-action-sidecar'
import './projects.css'

export interface ProjectDetailPageProps {
  projectId: string
  onBack?: () => void
  api?: ProjectsApi
}

interface PendingInstructionAttempt {
  issueId: string
  expectedVersion: string
  body: string
  requestKey: string
}

export function ProjectDetailPage({
  projectId,
  onBack,
  api = projectsApi,
}: ProjectDetailPageProps) {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  const queryIssueId = searchParams.get('issue')
  const queryThreadId = searchParams.get('thread')

  // 权威聚合状态
  const {
    data: snapshot,
    isLoading,
    error: queryError,
    refetch,
  } = useQuery({
    queryKey: queryKeys.projects.snapshot(projectId),
    queryFn: () => api.getProjectSnapshot(projectId),
  })

  const [actionError, setActionError] = useState<string | null>(null)

  // Modals
  const [isEditProjectOpen, setIsEditProjectOpen] = useState(false)
  const [isDeleteProjectOpen, setIsDeleteProjectOpen] = useState(false)
  const [isCreateIssueOpen, setIsCreateIssueOpen] = useState(false)
  const [selectedIssueId, setSelectedIssueId] = useState<string | null>(null)
  const [isManualModalOpen, setIsManualModalOpen] = useState(false)

  const effectiveIssueId = queryIssueId || selectedIssueId

  // Issue 详情查询（用于受控 AgentPane 门禁验证与元数据）
  const issueQuery = useQuery({
    queryKey: queryKeys.projects.issue(projectId, effectiveIssueId ?? ''),
    queryFn: () => api.getIssue(effectiveIssueId!),
    enabled: Boolean(effectiveIssueId),
  })
  const issueDetail = issueQuery.data

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
    enabled: Boolean(queryThreadId),
  })

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: Boolean(queryThreadId),
  })

  // 门禁验证：目标 thread 必须属于 detail.agentThreads 或 detail.runs
  const matchedAgentThread = issueDetail?.agentThreads?.find((t) => t.threadId === queryThreadId)
  const matchedRun = issueDetail?.runs?.find((r) => r.threadId === queryThreadId)
  const matchedAgentName = matchedAgentThread?.agentName || matchedRun?.agentName || null
  const isThreadValid = Boolean((matchedAgentThread || matchedRun) && matchedAgentName)

  const handleSelectIssue = (id: string | null) => {
    setSelectedIssueId(id)
    setIsManualModalOpen(false)
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      if (id) {
        next.set('issue', id)
      } else {
        next.delete('issue')
        next.delete('thread')
      }
      return next
    })
  }

  const handleOpenThread = (threadId: string) => {
    setIsManualModalOpen(false)
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      if (effectiveIssueId) {
        next.set('issue', effectiveIssueId)
      }
      next.set('thread', threadId)
      return next
    })
  }

  const handleCloseThread = () => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.delete('thread')
      return next
    })
  }

  const pendingInstructionRef = useRef<PendingInstructionAttempt | null>(null)

  const handleSubmitInstruction = async (text: string) => {
    if (!issueDetail) return
    const trimmed = text.trim()
    if (!trimmed) return

    const issueId = issueDetail.issue.id
    const expectedVersion = issueDetail.issue.version

    let attempt = pendingInstructionRef.current
    if (
      !attempt ||
      attempt.issueId !== issueId ||
      attempt.body !== trimmed
    ) {
      // 远端版本推进可能来自已提交但丢响应的请求，不能改变待重试的 payload。
      attempt = {
        issueId,
        expectedVersion,
        body: trimmed,
        requestKey: createUuid(),
      }
      pendingInstructionRef.current = attempt
    }

    await api.appendIssueActivity(attempt.issueId, {
      expectedVersion: attempt.expectedVersion,
      requestKey: attempt.requestKey,
      kind: 'INSTRUCTION',
      body: attempt.body,
    })
    // 成功后清空挂起的 attempt；若失败抛出异常中断执行，attempt 保留在 ref 中供重试
    pendingInstructionRef.current = null
    await invalidateSnapshot()
    await queryClient.invalidateQueries({
      queryKey: queryKeys.projects.issue(projectId, issueId),
    })
  }

  const invalidateSnapshot = async () => {
    await queryClient.invalidateQueries({
      queryKey: queryKeys.projects.snapshot(projectId),
      exact: true,
    })
  }

  const inflightIssuesRef = useRef<Set<string>>(new Set())

  // 看板与 Agent 视图共用的动作执行器：通过通用 executor 统一处理防重防覆盖、连点阻断、侧车持久化与受控错误处理
  const executeBoardIssueAction = async (
    issueId: string,
    expectedVersion: string,
    action: PendingIssueAction,
    runApi: (requestKey: string, expectedVersion: string) => Promise<unknown>,
    failureLabel: string,
  ): Promise<boolean> => {
    // 阻断连点：同一 issue 若正在执行中，忽略重复触发
    if (inflightIssuesRef.current.has(issueId)) {
      return false
    }

    const loadResult = loadPendingAction(issueId)
    if (loadResult.type === 'STORAGE_ERROR') {
      setActionError(`无法访问本地存储，已安全拦截操作: ${loadResult.error}`)
      return false
    }
    if (loadResult.type === 'CORRUPT') {
      setActionError('该 Issue 存在未确认结果的损坏写操作记录，为防覆盖已安全拦截；请在详情中确认或放弃')
      handleSelectIssue(issueId)
      return false
    }

    if (loadResult.type === 'VALID') {
      const existing = loadResult.action
      const isSameAction =
        existing.kind === action.kind &&
        isPayloadEqual(existing.payload, action.payload)

      if (isSameAction) {
        // 精确重试原请求
        inflightIssuesRef.current.add(issueId)
        try {
          setActionError(null)
          await runApi(existing.requestKey, existing.expectedVersion)
          clearPendingAction(issueId, existing.requestKey)
          await invalidateSnapshot()
          await queryClient.invalidateQueries({
            queryKey: queryKeys.projects.issue(projectId, issueId),
          })
          return true
        } catch (err) {
          if (!isNetworkUnknownError(err)) {
            clearPendingAction(issueId, existing.requestKey)
          }
          setActionError(err instanceof Error ? err.message : `${failureLabel}重试失败`)
          return false
        } finally {
          inflightIssuesRef.current.delete(issueId)
        }
      } else {
        setActionError('该 Issue 存在未确认结果的写操作，禁止新请求；请在详情中确认或放弃')
        handleSelectIssue(issueId)
        return false
      }
    }

    // 新请求：发送前落盘到侧车（fail closed，处于受控 try/catch 中）
    try {
      storePendingAction(issueId, action)
    } catch (err) {
      setActionError(`操作无法持久化侧车，已安全拦截: ${err instanceof Error ? err.message : String(err)}`)
      return false
    }

    inflightIssuesRef.current.add(issueId)
    try {
      setActionError(null)
      await runApi(action.requestKey, expectedVersion)
      clearPendingAction(issueId, action.requestKey)
      await invalidateSnapshot()
      await queryClient.invalidateQueries({
        queryKey: queryKeys.projects.issue(projectId, issueId),
      })
      return true
    } catch (err) {
      if (!isNetworkUnknownError(err)) {
        clearPendingAction(issueId, action.requestKey)
      }
      setActionError(err instanceof Error ? err.message : `${failureLabel}失败`)
      return false
    } finally {
      inflightIssuesRef.current.delete(issueId)
    }
  }

  const handleStopIssue = async () => {
    if (!issueDetail) return
    const targetIssueId = issueDetail.issue.id
    const targetVersion = issueDetail.issue.version
    const requestKey = createUuid()
    const action: PendingIssueAction = {
      issueId: targetIssueId,
      kind: 'STOP',
      requestKey,
      expectedVersion: targetVersion,
      payload: { detail: '用户在 Agent 视图中终止执行' },
      createdAt: new Date().toISOString(),
      isUnknown: true,
    }
    const ok = await executeBoardIssueAction(
      targetIssueId,
      targetVersion,
      action,
      (reqKey, expVer) =>
        api.stopIssue(targetIssueId, {
          expectedVersion: expVer,
          requestKey: reqKey,
          detail: '用户在 Agent 视图中终止执行',
        }),
      '终止 Issue',
    )
    if (!ok) {
      throw new Error('终止 Issue 失败')
    }
  }

  const handleTransitionIssue = async (
    issueId: string,
    expectedVersion: string,
    toState: string,
  ) => {
    const requestKey = createUuid()
    const action: PendingIssueAction = {
      issueId,
      kind: 'TRANSITION',
      requestKey,
      expectedVersion,
      payload: { toState },
      createdAt: new Date().toISOString(),
      isUnknown: true,
    }
    await executeBoardIssueAction(
      issueId,
      expectedVersion,
      action,
      (reqKey, expVer) => api.transitionIssue(issueId, { expectedVersion: expVer, requestKey: reqKey, toState }),
      '流转 Issue 状态',
    )
  }

  const handleBlockIssue = async (issueId: string, _expectedVersion: string) => {
    handleSelectIssue(issueId)
  }

  const handleRecoverIssue = async (issueId: string, expectedVersion: string) => {
    const requestKey = createUuid()
    const action: PendingIssueAction = {
      issueId,
      kind: 'RECOVER',
      requestKey,
      expectedVersion,
      payload: {},
      createdAt: new Date().toISOString(),
      isUnknown: true,
    }
    await executeBoardIssueAction(
      issueId,
      expectedVersion,
      action,
      (reqKey, expVer) => api.recoverIssue(issueId, { expectedVersion: expVer, requestKey: reqKey }),
      '恢复 Issue',
    )
  }

  const handleReopenIssue = async (issueId: string, expectedVersion: string) => {
    const requestKey = createUuid()
    const action: PendingIssueAction = {
      issueId,
      kind: 'REOPEN',
      requestKey,
      expectedVersion,
      payload: {},
      createdAt: new Date().toISOString(),
      isUnknown: true,
    }
    await executeBoardIssueAction(
      issueId,
      expectedVersion,
      action,
      (reqKey, expVer) => api.reopenIssue(issueId, { expectedVersion: expVer, requestKey: reqKey }),
      '重新打开 Issue',
    )
  }

  const handleResolveUnknownIssue = async (issueId: string, _expectedVersion: string) => {
    handleSelectIssue(issueId)
  }

  const handleArchiveIssue = async (issueId: string, expectedVersion: string) => {
    try {
      setActionError(null)
      await api.archiveIssue(issueId, { expectedVersion })
      await invalidateSnapshot()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '归档 Issue 失败')
    }
  }

  const handleUnarchiveIssue = async (issueId: string, expectedVersion: string) => {
    try {
      setActionError(null)
      await api.unarchiveIssue(issueId, { expectedVersion })
      await invalidateSnapshot()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '取消归档 Issue 失败')
    }
  }

  // 项目归档 / 取消归档
  const handleProjectArchiveToggle = async () => {
    if (!snapshot) {
      return
    }
    const project = snapshot.project
    try {
      setActionError(null)
      if (project.archivedAt) {
        await api.unarchiveProject(project.id, { expectedVersion: project.version })
      } else {
        await api.archiveProject(project.id, { expectedVersion: project.version })
      }
      await invalidateSnapshot()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '项目归档操作失败')
    }
  }

  const errorMessage = actionError || (queryError instanceof Error ? queryError.message : null)

  const isIssueDetailModalOpen = Boolean(effectiveIssueId && (!queryThreadId || isManualModalOpen))

  if (isLoading && !snapshot) {
    return (
      <div style={{ textAlign: 'center', padding: '64px', color: 'var(--fg-muted)' }}>
        加载项目详情中...
      </div>
    )
  }

  if (!snapshot) {
    return (
      <div style={{ padding: '32px' }}>
        {errorMessage && (
          <div className="form-error-banner" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{errorMessage}</span>
          </div>
        )}
        {onBack && (
          <button type="button" className="ghost-btn" onClick={onBack}>
            <ArrowLeft size={14} aria-hidden="true" />
            <span>返回项目列表</span>
          </button>
        )}
      </div>
    )
  }

  const project = snapshot.project

  return (
    <div className="project-detail-layout">
      <header className="project-detail-header">
        <div className="project-detail-nav">
          <div className="project-detail-nav-left">
            {onBack && (
              <button
                type="button"
                className="ghost-btn"
                onClick={onBack}
                title="返回项目列表"
                aria-label="返回项目列表"
              >
                <ArrowLeft size={16} aria-hidden="true" />
              </button>
            )}
            <h1 style={{ margin: 0, fontSize: '1.25rem', fontWeight: 600, color: 'var(--fg)' }}>
              {project.title}
            </h1>
            {project.archivedAt && <span className="badge badge-archived">已归档</span>}
          </div>

          <div className="project-detail-nav-right">
            <button
              type="button"
              className="ghost-btn"
              onClick={() => void refetch()}
              disabled={isLoading}
              title="刷新 Snapshot"
              aria-label="刷新项目 Snapshot"
            >
              <RefreshCw
                size={14}
                className={isLoading ? 'animate-spin' : ''}
                aria-hidden="true"
              />
            </button>

            <button
              type="button"
              className="ghost-btn"
              onClick={() => setIsEditProjectOpen(true)}
              title="编辑项目配置与工作流 JSON"
            >
              <Pencil size={14} aria-hidden="true" />
              <span>编辑 / 工作流</span>
            </button>

            <button
              type="button"
              className="ghost-btn"
              onClick={() => void handleProjectArchiveToggle()}
              title={project.archivedAt ? '取消归档' : '归档项目'}
            >
              <Archive size={14} aria-hidden="true" />
              <span>{project.archivedAt ? '取消归档' : '归档'}</span>
            </button>

            <button
              type="button"
              className="ghost-btn danger"
              onClick={() => setIsDeleteProjectOpen(true)}
              title="删除项目"
            >
              <Trash2 size={14} aria-hidden="true" />
              <span>删除</span>
            </button>
          </div>
        </div>

        <div className="project-detail-info-row">
          <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <span>YOLO:</span>
            <strong style={{ color: 'var(--fg)' }}>{project.yoloEnabled ? '开启' : '关闭'}</strong>
          </span>

          <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <Layers size={14} aria-hidden="true" />
            <span>工作流阶段:</span>
            <strong style={{ color: 'var(--fg)' }}>{project.workflow?.states?.length ?? 0} 个</strong>
          </span>

          <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <Calendar size={14} aria-hidden="true" />
            <span>更新于: {project.updatedAt}</span>
          </span>

          <span>
            版本: <code>{project.version}</code>
          </span>

          {project.description && (
            <span
              style={{
                color: 'var(--fg-muted)',
                maxWidth: '400px',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {project.description}
            </span>
          )}
        </div>
      </header>

      {errorMessage && (
        <div className="form-error-banner" role="alert" style={{ margin: '12px 20px 0 20px' }}>
          <AlertTriangle size={16} aria-hidden="true" />
          <span>{errorMessage}</span>
        </div>
      )}

      {/* 状态自然 token 看板 + 受控 AgentPane */}
      <div className="project-detail-body">
        <IssueBoard
          workflow={project.workflow}
          issues={snapshot.issues}
          onSelectIssue={(id) => handleSelectIssue(id)}
          onCreateIssue={() => setIsCreateIssueOpen(true)}
          onTransitionIssue={handleTransitionIssue}
          onBlockIssue={handleBlockIssue}
          onRecoverIssue={handleRecoverIssue}
          onReopenIssue={handleReopenIssue}
          onResolveUnknownIssue={handleResolveUnknownIssue}
          onArchiveIssue={handleArchiveIssue}
          onUnarchiveIssue={handleUnarchiveIssue}
        />

        {queryThreadId && (
          <aside className="project-agent-dock" data-testid="project-agent-dock">
            <div className="project-agent-dock-header">
              <div className="project-agent-dock-meta">
                <Bot size={16} aria-hidden="true" />
                <span className="project-agent-dock-title">
                  {issueDetail?.issue.title ?? 'Agent 线程'}
                </span>
                {matchedAgentName && <span className="badge badge-agent">{matchedAgentName}</span>}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={() => setIsManualModalOpen(true)}
                  title="查看完整 Issue 详情"
                  aria-label="查看完整 Issue 详情"
                >
                  <FileText size={14} aria-hidden="true" />
                  <span>Issue 详情</span>
                </button>
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={handleCloseThread}
                  title="关闭 Agent 视图"
                  aria-label="关闭 Agent 视图"
                >
                  <X size={14} aria-hidden="true" />
                </button>
              </div>
            </div>

            <div className="project-agent-dock-content">
              {issueQuery.isLoading ? (
                <div className="empty-tip">加载 Issue 与 Agent 线程中...</div>
              ) : !isThreadValid ? (
                <div style={{ padding: '24px' }}>
                  <div className="form-error-banner" role="alert">
                    <AlertTriangle size={16} aria-hidden="true" />
                    <span>目标 Thread 不属于该 Issue 绑定的 Agent 线程或 Run 记录，已拒绝接入</span>
                  </div>
                </div>
              ) : (
                <AgentPane
                  key={queryThreadId}
                  owner={{ type: 'ISSUE_AGENT', issueId: issueDetail!.issue.id, agentName: matchedAgentName! }}
                  paneId={`project-issue-${queryThreadId}`}
                  agents={agentsQuery.data?.results ?? []}
                  environments={environmentsQuery.data ?? []}
                  initialTarget={{ kind: 'BOUND_THREAD', threadId: queryThreadId }}
                  capabilities={{
                    allowNewSession: false,
                    allowSwitchAgent: false,
                    allowBranching: false,
                    allowGenericChat: false,
                  }}
                  onSubmitInstruction={handleSubmitInstruction}
                  onStop={handleStopIssue}
                  focused
                />
              )}
            </div>
          </aside>
        )}
      </div>

      {/* Modals */}
      <EditProjectModal
        isOpen={isEditProjectOpen}
        project={project}
        onClose={() => setIsEditProjectOpen(false)}
        onSuccess={() => void invalidateSnapshot()}
        api={api}
      />

      <DeleteProjectModal
        isOpen={isDeleteProjectOpen}
        project={project}
        onClose={() => setIsDeleteProjectOpen(false)}
        onSuccess={() => onBack?.()}
        api={api}
      />

      <CreateIssueModal
        isOpen={isCreateIssueOpen}
        projectId={projectId}
        onClose={() => setIsCreateIssueOpen(false)}
        onSuccess={() => void invalidateSnapshot()}
        api={api}
      />

      <IssueDetailModal
        key={effectiveIssueId ?? 'none'}
        isOpen={isIssueDetailModalOpen}
        projectId={projectId}
        issueId={effectiveIssueId}
        workflow={project.workflow}
        onClose={() => handleSelectIssue(null)}
        onUpdated={() => void invalidateSnapshot()}
        onOpenThread={handleOpenThread}
        api={api}
      />
    </div>
  )
}
