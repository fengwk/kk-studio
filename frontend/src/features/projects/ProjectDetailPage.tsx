import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AlertTriangle,
  Archive,
  ArrowLeft,
  Bot,
  Calendar,
  Pencil,
  RefreshCw,
  Trash2,
} from 'lucide-react'
import { CoordinatorConversation } from './components/CoordinatorConversation'
import { CreateIssueModal } from './components/CreateIssueModal'
import { DeleteProjectModal } from './components/DeleteProjectModal'
import { EditProjectModal } from './components/EditProjectModal'
import { IssueBoard } from './components/IssueBoard'
import { IssueDetailModal } from './components/IssueDetailModal'
import type { ProjectsApi } from './projects-api'
import { projectsApi } from './projects-api'
import type { IssueStatus, ProjectSnapshotDTO } from './types'
import { useProjectsInvalidation } from './useProjectsInvalidation'
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
  const [snapshot, setSnapshot] = useState<ProjectSnapshotDTO | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  // Dialogs
  const [isEditProjectOpen, setIsEditProjectOpen] = useState(false)
  const [isDeleteProjectOpen, setIsDeleteProjectOpen] = useState(false)
  const [isCreateIssueOpen, setIsCreateIssueOpen] = useState(false)
  const [selectedIssueId, setSelectedIssueId] = useState<string | null>(null)
  const loadRequestIdRef = useRef(0)

  const loadSnapshot = useCallback(async () => {
    const requestId = ++loadRequestIdRef.current
    setIsLoading(true)
    setErrorMessage(null)
    try {
      const data = await api.getProjectSnapshot(projectId)
      if (loadRequestIdRef.current === requestId) {
        setSnapshot(data)
      }
    } catch (err) {
      if (loadRequestIdRef.current === requestId) {
        setErrorMessage(err instanceof Error ? err.message : '获取项目 Snapshot 失败')
      }
    } finally {
      if (loadRequestIdRef.current === requestId) {
        setIsLoading(false)
      }
    }
  }, [api, projectId])

  useEffect(() => {
    void loadSnapshot()
    return () => {
      loadRequestIdRef.current += 1
    }
  }, [loadSnapshot])

  // Invalidation subscription
  useProjectsInvalidation((payload) => {
    if (!payload?.projectId || payload.projectId === projectId) {
      void loadSnapshot()
    }
  })

  // Status transitions from board
  const handleChangeIssueStatus = async (
    issueId: string,
    expectedVersion: string,
    status: IssueStatus,
  ) => {
    try {
      await api.changeIssueStatus(issueId, { expectedVersion, status })
      await loadSnapshot()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '变更 Issue 状态失败')
    }
  }

  // Cancel Issue from board
  const handleCancelIssue = async (issueId: string, expectedVersion: string) => {
    try {
      await api.cancelIssue(issueId, { expectedVersion })
      await loadSnapshot()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '取消 Issue 失败')
    }
  }

  // Archive / Unarchive Issue from board
  const handleArchiveIssue = async (issueId: string, expectedVersion: string) => {
    try {
      await api.archiveIssue(issueId, { expectedVersion })
      await loadSnapshot()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '归档 Issue 失败')
    }
  }

  const handleUnarchiveIssue = async (issueId: string, expectedVersion: string) => {
    try {
      await api.unarchiveIssue(issueId, { expectedVersion })
      await loadSnapshot()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '取消归档 Issue 失败')
    }
  }

  // Archive / Unarchive Project
  const handleProjectArchiveToggle = async () => {
    if (!snapshot) {
      return
    }
    const project = snapshot.project
    try {
      if (project.archivedAt) {
        await api.unarchiveProject(project.id, { expectedVersion: project.version })
      } else {
        await api.archiveProject(project.id, { expectedVersion: project.version })
      }
      await loadSnapshot()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '项目归档操作失败')
    }
  }

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
            {project.archivedAt && (
              <span className="badge badge-archived">已归档</span>
            )}
          </div>

          <div className="project-detail-nav-right">
            <button
              type="button"
              className="ghost-btn"
              onClick={() => void loadSnapshot()}
              disabled={isLoading}
              title="刷新 Snapshot"
              aria-label="刷新项目 Snapshot"
            >
              <RefreshCw size={14} className={isLoading ? 'animate-spin' : ''} aria-hidden="true" />
            </button>

            <button
              type="button"
              className="ghost-btn"
              onClick={() => setIsEditProjectOpen(true)}
              title="编辑项目"
            >
              <Pencil size={14} aria-hidden="true" />
              <span>编辑</span>
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
            <Bot size={14} aria-hidden="true" />
            <span>Coordinator:</span>
            <strong style={{ color: 'var(--fg)' }}>
              {project.coordinatorAgentName}
            </strong>
          </span>

          <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <Calendar size={14} aria-hidden="true" />
            <span>更新于: {project.updatedAt}</span>
          </span>

          <span>
            版本: <code>{project.version}</code>
          </span>

          {project.description && (
            <span style={{ color: 'var(--fg-muted)', maxWidth: '400px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
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

      <div className="project-detail-body">
        {/* Left: 6-Column Issue Board */}
        <IssueBoard
          issues={snapshot.issues}
          onSelectIssue={(id) => setSelectedIssueId(id)}
          onCreateIssue={() => setIsCreateIssueOpen(true)}
          onChangeIssueStatus={handleChangeIssueStatus}
          onCancelIssue={handleCancelIssue}
          onArchiveIssue={handleArchiveIssue}
          onUnarchiveIssue={handleUnarchiveIssue}
        />

        {/* Right: Coordinator conversation */}
        <CoordinatorConversation
          projectId={projectId}
          snapshot={snapshot}
          onCommandAccepted={() => void loadSnapshot()}
          api={api}
        />
      </div>

      {/* Modals */}
      <EditProjectModal
        isOpen={isEditProjectOpen}
        project={project}
        onClose={() => setIsEditProjectOpen(false)}
        onSuccess={() => void loadSnapshot()}
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
        onSuccess={() => void loadSnapshot()}
        api={api}
      />

      <IssueDetailModal
        isOpen={Boolean(selectedIssueId)}
        issueId={selectedIssueId}
        projectIssues={snapshot.issues}
        onClose={() => setSelectedIssueId(null)}
        onUpdated={() => void loadSnapshot()}
        api={api}
      />
    </div>
  )
}
