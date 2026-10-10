import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArrowRight, FolderKanban, Pencil, Trash2 } from 'lucide-react'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { SearchField } from '@/shared/ui/controls/SearchField'
import { CreateProjectModal } from './components/CreateProjectModal'
import { DeleteProjectModal } from './components/DeleteProjectModal'
import { EditProjectModal } from './components/EditProjectModal'
import type { ProjectsApi } from './projects-api'
import { projectsApi } from './projects-api'
import type { ProjectDTO } from './types'
import { useI18n } from '@/shared/i18n'
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
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [includeArchived, setIncludeArchived] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)

  const {
    data: projects = [],
    isLoading,
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
        p.description.toLowerCase().includes(q),
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
      setActionError(err instanceof Error ? err.message : t('projects.archiveFailed'))
    }
  }

  const retryLoad = async () => {
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
        <h1 className="projects-title">{t('projects.pageTitle')}</h1>
        <div className="projects-controls">
          <SearchField
            value={searchQuery}
            onChange={setSearchQuery}
            placeholder={t('projects.searchPlaceholder')}
            aria-label={t('projects.searchLabel')}
          />
          <Checkbox
            checked={includeArchived}
            onChange={setIncludeArchived}
            label={t('projects.includeArchived')}
          />
        </div>
      </header>

      {errorMessage && (
        <div className="projects-status">
          <StateBlock tone="danger" title={errorMessage} />
          <Button onClick={() => void retryLoad()}>{t('projects.retry')}</Button>
        </div>
      )}

      {isLoading && projects.length === 0 && (
        <ResourceCardSkeleton label={t('projects.loading')} />
      )}

      {Boolean(searchQuery.trim()) && filteredProjects.length === 0 && !isLoading && (
        <StateBlock title={t('projects.empty.noMatch')} />
      )}

      {!(isLoading && projects.length === 0) && (
        <ResourceGrid>
        <CreateCard
          title={t('projects.create')}
          subtitle={t('projects.createCardSubtitle')}
          onClick={() => setIsCreateOpen(true)}
        />

        {filteredProjects.map((project) => (
          <ResourceCard
            key={project.id}
            className={project.archivedAt ? 'is-archived' : undefined}
            icon={<FolderKanban size={20} aria-hidden="true" />}
            title={project.title}
            subtitle={project.description || t('projects.noDescription')}
            meta={[
              [
                'YOLO',
                project.yoloEnabled ? t('projects.yoloEnabled') : t('projects.yoloDisabled'),
              ],
              [
                t('projects.meta.stages'),
                t('projects.meta.stagesValue', { count: project.workflow?.states?.length ?? 0 }),
              ],
              [t('projects.meta.updatedAt'), project.updatedAt],
              [t('projects.meta.nextIssueNumber'), `#${project.nextIssueNumber}`],
            ]}
            actions={
              <>
                {onSelectProject && (
                  <Button onClick={() => onSelectProject(project.id)}>
                    <span>{t('projects.enter')}</span>
                    <ArrowRight size={14} aria-hidden="true" />
                  </Button>
                )}
                <div className="project-card-actions-right">
                  <IconButton
                    label={t('projects.card.edit', { title: project.title })}
                    size="compact"
                    onClick={() => setEditingProject(project)}
                  >
                    <Pencil aria-hidden="true" />
                  </IconButton>
                  <IconButton
                    label={
                      project.archivedAt
                        ? t('projects.card.unarchive', { title: project.title })
                        : t('projects.card.archive', { title: project.title })
                    }
                    size="compact"
                    onClick={() => void handleArchiveToggle(project)}
                  >
                    <Archive aria-hidden="true" />
                  </IconButton>
                  <IconButton
                    label={t('projects.card.delete', { title: project.title })}
                    size="compact"
                    danger
                    onClick={() => setDeletingProject(project)}
                  >
                    <Trash2 aria-hidden="true" />
                  </IconButton>
                </div>
              </>
            }
          />
        ))}
      </ResourceGrid>
      )}

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
