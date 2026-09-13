import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  Archive,
  ArrowRight,
  Bot,
  Calendar,
  Folder,
  FolderPlus,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Trash2,
} from 'lucide-react'
import { CreateProjectModal } from './components/CreateProjectModal'
import { DeleteProjectModal } from './components/DeleteProjectModal'
import { EditProjectModal } from './components/EditProjectModal'
import type { ProjectsApi } from './projects-api'
import { projectsApi } from './projects-api'
import type { ProjectDTO } from './types'
import { useProjectsInvalidation } from './useProjectsInvalidation'
import './projects.css'

export interface ProjectsPageProps {
  onSelectProject?: (projectId: string) => void
  api?: ProjectsApi
}

export function ProjectsPage({
  onSelectProject,
  api = projectsApi,
}: ProjectsPageProps) {
  const [projects, setProjects] = useState<ProjectDTO[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [includeArchived, setIncludeArchived] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')

  // Modals
  const [isCreateOpen, setIsCreateOpen] = useState(false)
  const [editingProject, setEditingProject] = useState<ProjectDTO | null>(null)
  const [deletingProject, setDeletingProject] = useState<ProjectDTO | null>(null)
  const loadRequestIdRef = useRef(0)

  const loadProjects = useCallback(async () => {
    const requestId = ++loadRequestIdRef.current
    setIsLoading(true)
    setErrorMessage(null)
    try {
      const data = await api.listProjects(includeArchived)
      if (loadRequestIdRef.current === requestId) {
        setProjects(data)
      }
    } catch (err) {
      if (loadRequestIdRef.current === requestId) {
        setErrorMessage(err instanceof Error ? err.message : '获取项目列表失败')
      }
    } finally {
      if (loadRequestIdRef.current === requestId) {
        setIsLoading(false)
      }
    }
  }, [api, includeArchived])

  useEffect(() => {
    void loadProjects()
    return () => {
      loadRequestIdRef.current += 1
    }
  }, [loadProjects])

  // Invalidation subscription
  useProjectsInvalidation(() => {
    void loadProjects()
  })

  const filteredProjects = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) {
      return projects
    }
    return projects.filter(
      (p) =>
        p.title.toLowerCase().includes(q) ||
        p.description.toLowerCase().includes(q) ||
        p.coordinatorAgentName.toLowerCase().includes(q),
    )
  }, [projects, searchQuery])

  const handleArchiveToggle = async (project: ProjectDTO) => {
    try {
      if (project.archivedAt) {
        await api.unarchiveProject(project.id, { expectedVersion: project.version })
      } else {
        await api.archiveProject(project.id, { expectedVersion: project.version })
      }
      await loadProjects()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '归档操作失败')
    }
  }

  return (
    <main className="projects-container">
      <header className="projects-header">
        <div className="projects-header-left">
          <h1 className="projects-title">项目管理 (Projects)</h1>
          <button
            type="button"
            className="ghost-btn"
            onClick={() => void loadProjects()}
            disabled={isLoading}
            title="刷新列表"
            aria-label="刷新项目列表"
          >
            <RefreshCw size={14} className={isLoading ? 'animate-spin' : ''} aria-hidden="true" />
          </button>
        </div>

        <div className="projects-controls">
          <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
            <Search
              size={14}
              style={{ position: 'absolute', left: '10px', color: 'var(--fg-muted)' }}
              aria-hidden="true"
            />
            <input
              type="text"
              className="projects-search-input"
              style={{ paddingLeft: '32px' }}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="搜索项目名称或描述..."
              aria-label="搜索项目"
            />
          </div>

          <label className="projects-checkbox-label">
            <input
              type="checkbox"
              checked={includeArchived}
              onChange={(e) => setIncludeArchived(e.target.checked)}
            />
            <span>显示已归档</span>
          </label>

          <button
            type="button"
            className="btn-primary"
            onClick={() => setIsCreateOpen(true)}
            style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
            aria-label="新建项目"
          >
            <Plus size={14} aria-hidden="true" />
            <span>新建项目</span>
          </button>
        </div>
      </header>

      {errorMessage && (
        <div className="form-error-banner" role="alert" style={{ marginBottom: '16px' }}>
          <AlertTriangle size={16} aria-hidden="true" />
          <span>{errorMessage}</span>
        </div>
      )}

      {isLoading && projects.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '48px', color: 'var(--fg-muted)' }}>
          加载项目中...
        </div>
      ) : filteredProjects.length === 0 ? (
        <div
          style={{
            textAlign: 'center',
            padding: '64px 24px',
            background: 'var(--bg-surface)',
            border: '1px dashed var(--border)',
            borderRadius: 'var(--radius-md)',
          }}
        >
          <Folder size={48} style={{ color: 'var(--fg-muted)', marginBottom: '12px' }} aria-hidden="true" />
          <h3 style={{ margin: '0 0 8px 0', color: 'var(--fg)' }}>暂无项目</h3>
          <p style={{ margin: '0 0 16px 0', color: 'var(--fg-muted)', fontSize: '0.875rem' }}>
            {searchQuery ? '没有找到符合搜索条件的项目' : '创建首个项目以管理 Issue 任务看板和 Coordinator 对话'}
          </p>
          {!searchQuery && (
            <button
              type="button"
              className="btn-primary"
              onClick={() => setIsCreateOpen(true)}
              aria-label="新建项目"
            >
              新建项目
            </button>
          )}
        </div>
      ) : (
        <div className="projects-grid">
          {/* Create Card shortcut */}
          <button
            type="button"
            className="create-card"
            onClick={() => setIsCreateOpen(true)}
            aria-label="创建新项目卡片"
            style={{ minHeight: '180px', textAlign: 'center', cursor: 'pointer' }}
          >
            <FolderPlus size={32} style={{ color: 'var(--green-primary)', marginBottom: '8px' }} aria-hidden="true" />
            <strong>新建项目</strong>
            <small>配置 Coordinator 编排与多 Issue 看板</small>
          </button>

          {filteredProjects.map((project) => (
            <article
              key={project.id}
              className={`project-card ${project.archivedAt ? 'is-archived' : ''}`}
            >
              <div>
                <div className="project-card-head">
                  <div className="project-card-title-wrap">
                    <h2
                      className="project-card-title"
                      style={{ cursor: onSelectProject ? 'pointer' : 'default' }}
                      onClick={() => onSelectProject?.(project.id)}
                    >
                      {project.title}
                    </h2>
                  </div>
                  {project.archivedAt && (
                    <span className="badge badge-archived">已归档</span>
                  )}
                </div>

                <p className="project-card-desc">
                  {project.description || '（无项目描述）'}
                </p>

                <div className="project-meta-list">
                  <div className="project-meta-row">
                    <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                      <Bot size={14} aria-hidden="true" />
                      <span>Coordinator:</span>
                    </span>
                    <strong style={{ color: 'var(--fg)' }}>
                      {project.coordinatorAgentName}
                    </strong>
                  </div>

                  <div className="project-meta-row">
                    <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                      <Calendar size={14} aria-hidden="true" />
                      <span>更新时间:</span>
                    </span>
                    <span>{project.updatedAt}</span>
                  </div>

                  <div className="project-meta-row">
                    <span>下一个 Issue 编号:</span>
                    <code>#{project.nextIssueNumber}</code>
                  </div>
                </div>
              </div>

              <div className="project-card-actions">
                {onSelectProject ? (
                  <button
                    type="button"
                    className="btn-primary"
                    style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
                    onClick={() => onSelectProject(project.id)}
                  >
                    <span>进入项目</span>
                    <ArrowRight size={14} aria-hidden="true" />
                  </button>
                ) : (
                  <span />
                )}

                <div className="project-card-actions-right">
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={() => setEditingProject(project)}
                    title="编辑项目"
                    aria-label={`编辑项目 ${project.title}`}
                  >
                    <Pencil size={14} aria-hidden="true" />
                  </button>

                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={() => void handleArchiveToggle(project)}
                    title={project.archivedAt ? '取消归档' : '归档项目'}
                    aria-label={`${project.archivedAt ? '取消归档' : '归档'}项目 ${project.title}`}
                  >
                    <Archive size={14} aria-hidden="true" />
                  </button>

                  <button
                    type="button"
                    className="ghost-btn danger"
                    onClick={() => setDeletingProject(project)}
                    title="删除项目"
                    aria-label={`删除项目 ${project.title}`}
                  >
                    <Trash2 size={14} aria-hidden="true" />
                  </button>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}

      {/* Modals */}
      <CreateProjectModal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        onSuccess={(created) => {
          void loadProjects()
          onSelectProject?.(created.id)
        }}
        api={api}
      />

      <EditProjectModal
        isOpen={Boolean(editingProject)}
        project={editingProject}
        onClose={() => setEditingProject(null)}
        onSuccess={() => void loadProjects()}
        api={api}
      />

      <DeleteProjectModal
        isOpen={Boolean(deletingProject)}
        project={deletingProject}
        onClose={() => setDeletingProject(null)}
        onSuccess={() => void loadProjects()}
        api={api}
      />
    </main>
  )
}
