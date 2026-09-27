import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertTriangle,
  Archive,
  ArrowLeft,
  Calendar,
  Layers,
  Pencil,
  RefreshCw,
  Trash2,
} from 'lucide-react'
import { CreateIssueModal } from './components/CreateIssueModal'
import { DeleteProjectModal } from './components/DeleteProjectModal'
import { EditProjectModal } from './components/EditProjectModal'
import { IssueBoard } from './components/IssueBoard'
import { IssueDetailModal } from './components/IssueDetailModal'
import type { ProjectsApi } from './projects-api'
import { projectsApi } from './projects-api'
import { createUuid } from '@/shared/lib/uuid'
import { queryKeys } from '@/shared/lib/query-keys'
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
  const queryClient = useQueryClient()

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

  const invalidateSnapshot = async () => {
    await queryClient.invalidateQueries({
      queryKey: queryKeys.projects.snapshot(projectId),
      exact: true,
    })
  }

  // 看板上的动作：每个写操作冻结 requestKey 支持相同重试
  const handleTransitionIssue = async (
    issueId: string,
    expectedVersion: string,
    toState: string,
  ) => {
    const requestKey = createUuid()
    try {
      setActionError(null)
      await api.transitionIssue(issueId, { expectedVersion, requestKey, toState })
      await invalidateSnapshot()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '流转 Issue 状态失败')
    }
  }

  const handleBlockIssue = async (issueId: string, _expectedVersion: string) => {
    setSelectedIssueId(issueId)
  }

  const handleRecoverIssue = async (issueId: string, expectedVersion: string) => {
    const requestKey = createUuid()
    try {
      setActionError(null)
      await api.recoverIssue(issueId, { expectedVersion, requestKey })
      await invalidateSnapshot()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '恢复 Issue 失败')
    }
  }

  const handleReopenIssue = async (issueId: string, expectedVersion: string) => {
    const requestKey = createUuid()
    try {
      setActionError(null)
      await api.reopenIssue(issueId, { expectedVersion, requestKey })
      await invalidateSnapshot()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '重新打开 Issue 失败')
    }
  }

  const handleResolveUnknownIssue = async (issueId: string, _expectedVersion: string) => {
    setSelectedIssueId(issueId)
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

      {/* 状态自然 token 看板 */}
      <div className="project-detail-body">
        <IssueBoard
          workflow={project.workflow}
          issues={snapshot.issues}
          onSelectIssue={(id) => setSelectedIssueId(id)}
          onCreateIssue={() => setIsCreateIssueOpen(true)}
          onTransitionIssue={handleTransitionIssue}
          onBlockIssue={handleBlockIssue}
          onRecoverIssue={handleRecoverIssue}
          onReopenIssue={handleReopenIssue}
          onResolveUnknownIssue={handleResolveUnknownIssue}
          onArchiveIssue={handleArchiveIssue}
          onUnarchiveIssue={handleUnarchiveIssue}
        />
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
        isOpen={Boolean(selectedIssueId)}
        projectId={projectId}
        issueId={selectedIssueId}
        workflow={project.workflow}
        onClose={() => setSelectedIssueId(null)}
        onUpdated={() => void invalidateSnapshot()}
        api={api}
      />
    </div>
  )
}
