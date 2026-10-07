import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation, useSearchParams } from 'react-router'
import {
  AlertTriangle,
  Archive,
  ArrowLeft,
  Bot,
  FileText,
  Pencil,
  Plus,
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
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { SearchField } from '@/shared/ui/controls/SearchField'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import type { ProjectsApi } from './projects-api'
import { projectsApi } from './projects-api'
import { useI18n } from '@/shared/i18n'
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

export function ProjectDetailPage({
  projectId,
  onBack,
  api = projectsApi,
}: ProjectDetailPageProps) {
  const { t } = useI18n()
  const location = useLocation()
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
  const [isManualModalOpen, setIsManualModalOpen] = useState(false)

  // 看板筛选（搜索 / 归档）由页面头部统一持有，IssueBoard 只负责按结果渲染列与卡片。
  const [searchQuery, setSearchQuery] = useState('')
  const [includeArchived, setIncludeArchived] = useState(false)

  // 路由或参数变化时关闭 manualModal，避免前进后退复活弹窗
  useEffect(() => {
    setIsManualModalOpen(false)
  }, [location.key, queryIssueId, queryThreadId])

  // Issue 详情查询（用于受控 AgentPane 门禁验证与元数据）
  const issueQuery = useQuery({
    queryKey: queryKeys.projects.issue(projectId, queryIssueId ?? ''),
    queryFn: () => api.getIssue(queryIssueId!),
    enabled: Boolean(queryIssueId),
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
      if (queryIssueId) {
        next.set('issue', queryIssueId)
      }
      next.set('thread', threadId)
      return next
    })
  }

  const handleCloseThread = () => {
    setIsManualModalOpen(false)
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.delete('thread')
      next.delete('issue')
      return next
    })
  }

  const handleCloseIssueDetail = () => {
    setIsManualModalOpen(false)
    if (!queryThreadId) {
      handleSelectIssue(null)
    }
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
      setActionError(t('projects.detail.storageUnavailable', { reason: loadResult.error }))
      return false
    }
    if (loadResult.type === 'CORRUPT') {
      setActionError(t('projects.detail.corruptPending'))
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
          setActionError(
            err instanceof Error ? err.message : t('projects.detail.retryFailed', { label: failureLabel }),
          )
          return false
        } finally {
          inflightIssuesRef.current.delete(issueId)
        }
      } else {
        setActionError(t('projects.detail.pendingWriteBlocked'))
        handleSelectIssue(issueId)
        return false
      }
    }

    // 新请求：发送前落盘到侧车（fail closed，处于受控 try/catch 中）
    try {
      storePendingAction(issueId, action)
    } catch (err) {
      setActionError(
        t('projects.detail.storePendingFailed', {
          reason: err instanceof Error ? err.message : String(err),
        }),
      )
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
      setActionError(
        err instanceof Error ? err.message : t('projects.detail.actionFailed', { label: failureLabel }),
      )
      return false
    } finally {
      inflightIssuesRef.current.delete(issueId)
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
      t('projects.detail.action.transition'),
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
      t('projects.detail.action.recover'),
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
      t('projects.detail.action.reopen'),
    )
  }

  const handleResolveUnknownIssue = async (issueId: string, _expectedVersion: string) => {
    handleSelectIssue(issueId)
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
      setActionError(err instanceof Error ? err.message : t('projects.detail.archiveFailed'))
    }
  }

  const errorMessage = actionError || (queryError instanceof Error ? queryError.message : null)

  const isIssueDetailModalOpen = Boolean(queryIssueId && (!queryThreadId || isManualModalOpen))

  if (!snapshot) {
    // 加载失败、已删除或仍在加载的项目看板：始终保留返回入口与明确重试，不因隐藏全局导航而失去退出路径。
    return (
      <div className="project-detail-layout">
        <header className="project-detail-header">
          <div className="project-detail-nav">
            <div className="project-detail-nav-left">
              {onBack && (
                <IconButton label={t('projects.detail.back')} onClick={onBack}>
                  <ArrowLeft size={16} aria-hidden="true" />
                </IconButton>
              )}
              <h1 className="project-detail-title">{t('projects.detail.boardTitle')}</h1>
            </div>
          </div>
        </header>
        <div className="project-detail-body project-detail-status">
          {isLoading ? (
            <StateBlock title={t('projects.detail.loading')} />
          ) : (
            <div className="project-detail-status-actions">
              <StateBlock tone="danger" title={errorMessage ?? t('projects.detail.loadFailed')} />
              <Button onClick={() => void refetch()}>{t('projects.retry')}</Button>
            </div>
          )}
        </div>
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
              <IconButton label={t('projects.detail.back')} onClick={onBack}>
                <ArrowLeft size={16} aria-hidden="true" />
              </IconButton>
            )}
            <h1 className="project-detail-title">{project.title}</h1>
            {project.archivedAt && (
              <span className="badge badge-archived">{t('projects.archived')}</span>
            )}
            <span className="badge" title={t('projects.detail.yoloBadge')}>
              YOLO {project.yoloEnabled ? t('projects.yoloEnabled') : t('projects.yoloDisabled')}
            </span>
          </div>
        </div>

        <div className="project-detail-toolbar">
          <SearchField
            value={searchQuery}
            onChange={setSearchQuery}
            placeholder={t('projects.detail.searchPlaceholder')}
            aria-label={t('projects.detail.searchIssues')}
          />
          <Checkbox
            checked={includeArchived}
            onChange={setIncludeArchived}
            label={t('projects.includeArchived')}
          />
          <div className="project-detail-toolbar-actions">
            <Button onClick={() => setIsCreateIssueOpen(true)}>
              <Plus size={14} aria-hidden="true" />
              <span>{t('projects.issue.create')}</span>
            </Button>
            <Button variant="ghost" onClick={() => setIsEditProjectOpen(true)}>
              <Pencil size={14} aria-hidden="true" />
              <span>{t('projects.detail.editWorkflow')}</span>
            </Button>
            <Button variant="ghost" onClick={() => void handleProjectArchiveToggle()}>
              <Archive size={14} aria-hidden="true" />
              <span>
                {project.archivedAt ? t('projects.unarchive') : t('projects.detail.archiveAction')}
              </span>
            </Button>
            <Button variant="ghost" danger onClick={() => setIsDeleteProjectOpen(true)}>
              <Trash2 size={14} aria-hidden="true" />
              <span>{t('projects.detail.deleteAction')}</span>
            </Button>
          </div>
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
          searchQuery={searchQuery}
          includeArchived={includeArchived}
          onSelectIssue={(id) => handleSelectIssue(id)}
          onTransitionIssue={handleTransitionIssue}
          onBlockIssue={handleBlockIssue}
          onRecoverIssue={handleRecoverIssue}
          onReopenIssue={handleReopenIssue}
          onResolveUnknownIssue={handleResolveUnknownIssue}
        />

        {queryThreadId && (
          <aside className="project-agent-dock" data-testid="project-agent-dock">
            <div className="project-agent-dock-header">
              <div className="project-agent-dock-meta">
                <Bot size={16} aria-hidden="true" />
                <span className="project-agent-dock-title">
                  {issueDetail?.issue.title ?? t('projects.detail.threadTitle')}
                </span>
                {matchedAgentName && <span className="badge badge-agent">{matchedAgentName}</span>}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                <Button
                  variant="ghost"
                  size="compact"
                  onClick={() => setIsManualModalOpen(true)}
                  title={t('projects.detail.viewIssueDetail')}
                  aria-label={t('projects.detail.viewIssueDetail')}
                >
                  <FileText size={14} aria-hidden="true" />
                  <span>{t('projects.detail.issueDetail')}</span>
                </Button>
                <IconButton
                  label={t('projects.detail.closeAgentView')}
                  size="compact"
                  onClick={handleCloseThread}
                >
                  <X size={14} aria-hidden="true" />
                </IconButton>
              </div>
            </div>

            <div className="project-agent-dock-content">
              {issueQuery.isLoading ? (
                <StateBlock title={t('projects.detail.loadingThread')} />
              ) : !isThreadValid ? (
                <div style={{ padding: '24px' }}>
                  <div className="form-error-banner" role="alert">
                    <AlertTriangle size={16} aria-hidden="true" />
                    <span>{t('projects.detail.threadRejected')}</span>
                  </div>
                </div>
              ) : (
                <AgentPane
                  key={queryThreadId}
                  paneId={`project-issue-${queryThreadId}`}
                  agents={agentsQuery.data?.results ?? []}
                  environments={environmentsQuery.data ?? []}
                  initialTarget={{ kind: 'BOUND_THREAD', threadId: queryThreadId }}
                  capabilities={{
                    allowNewSession: false,
                    allowBranching: false,
                  }}
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
        initialTab="workflow"
        project={project}
        snapshot={snapshot}
        snapshotError={queryError}
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
        key={queryIssueId ?? 'none'}
        isOpen={isIssueDetailModalOpen}
        projectId={projectId}
        issueId={queryIssueId}
        workflow={project.workflow}
        onClose={handleCloseIssueDetail}
        onUpdated={() => void invalidateSnapshot()}
        onOpenThread={handleOpenThread}
        api={api}
      />
    </div>
  )
}
