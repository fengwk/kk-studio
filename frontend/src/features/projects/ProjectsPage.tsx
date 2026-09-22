import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertTriangle,
  Archive,
  ArrowRight,
  Bot,
  Calendar,
  Pencil,
  RefreshCw,
  Search,
  Trash2,
} from 'lucide-react'
import { CreateCard } from '@/shared/ui/console/AiConsoleCommonCards'
import { Checkbox } from '@/shared/ui/console/Checkbox'
import { CreateProjectModal } from './components/CreateProjectModal'
import { DeleteProjectModal } from './components/DeleteProjectModal'
import { EditProjectModal } from './components/EditProjectModal'
import type { ProjectsApi } from './projects-api'
import { projectsApi } from './projects-api'
import type { ProjectDTO } from './types'
import { queryKeys } from '@/shared/lib/query-keys'
import './projects.css'

export interface ProjectsPageProps {
  onSelectProject?: (projectId: string) => void
  api?: ProjectsApi
}

export function ProjectsPage({
  onSelectProject,
  api = projectsApi,
}: ProjectsPageProps) {
  const queryClient = useQueryClient()
  const [includeArchived, setIncludeArchived] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)

  const {
    data: projects = [],
    isLoading,
    isFetching,
    error: queryError,
    refetch,
  } = useQuery({
    queryKey: queryKeys.projects.list(includeArchived),
    queryFn: () => api.listProjects(includeArchived),
  })

  // Modals
  const [isCreateOpen, setIsCreateOpen] = useState(false)
  const [editingProject, setEditingProject] = useState<ProjectDTO | null>(null)
  const [deletingProject, setDeletingProject] = useState<ProjectDTO | null>(null)

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
      setActionError(null)
      if (project.archivedAt) {
        await api.unarchiveProject(project.id, { expectedVersion: project.version })
      } else {
        await api.archiveProject(project.id, { expectedVersion: project.version })
      }
      await queryClient.invalidateQueries({ queryKey: queryKeys.projects.lists() })
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '归档操作失败')
    }
  }

  const refreshProjects = async () => {
    setActionError(null)
    await refetch()
  }

  const invalidateProjectLists = async () => {
    setActionError(null)
    await queryClient.invalidateQueries({ queryKey: queryKeys.projects.lists() })
  }

  const errorMessage = actionError || (queryError instanceof Error ? queryError.message : null)

  return (
    <main className="projects-container">
      <header className="projects-header">
        <div className="projects-header-left">
          <h1 className="projects-title">项目管理 (Projects)</h1>
          <button
            type="button"
            className="ghost-btn"
            onClick={() => void refreshProjects()}
            disabled={isFetching}
            title="刷新列表"
            aria-label="刷新项目列表"
          >
            <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} aria-hidden="true" />
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

          <Checkbox
            checked={includeArchived}
            onChange={setIncludeArchived}
            label="显示已归档"
          />
        </div>
      </header>

      {errorMessage && (
        <div className="form-error-banner" role="alert" style={{ marginBottom: '16px' }}>
          <AlertTriangle size={16} aria-hidden="true" />
          <span>{errorMessage}</span>
        </div>
      )}

      {isLoading && projects.length === 0 && (
        <div style={{ textAlign: 'center', padding: '48px', color: 'var(--fg-muted)' }}>
          加载项目中...
        </div>
      )}

      {Boolean(searchQuery.trim()) && filteredProjects.length === 0 && !isLoading && (
        <div className="state-block" style={{ marginBottom: '20px' }}>
          <div>
            <strong>暂无匹配项目</strong>
            <p style={{ margin: '4px 0 0 0', color: 'var(--fg-dim)', fontSize: '13px' }}>
              没有找到符合搜索条件的项目
            </p>
          </div>
        </div>
      )}

      <div className="cards-grid">
        <CreateCard
          title="新建项目"
          subtitle="配置 Coordinator 编排与多 Issue 看板"
          onClick={() => setIsCreateOpen(true)}
        />

        {filteredProjects.map((project) => (
          <article
            key={project.id}
            className={`info-card project-card ${project.archivedAt ? 'is-archived' : ''}`}
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

      {/* Modals */}
      <CreateProjectModal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        onSuccess={(created) => {
          void invalidateProjectLists()
          onSelectProject?.(created.id)
        }}
        api={api}
      />

      <EditProjectModal
        isOpen={Boolean(editingProject)}
        project={editingProject}
        onClose={() => setEditingProject(null)}
        onSuccess={() => void invalidateProjectLists()}
        api={api}
      />

      <DeleteProjectModal
        isOpen={Boolean(deletingProject)}
        project={deletingProject}
        onClose={() => setDeletingProject(null)}
        onSuccess={() => void invalidateProjectLists()}
        api={api}
      />
    </main>
  )
}
