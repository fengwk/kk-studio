import { useState } from 'react'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { Button } from '@/shared/ui/controls/Button'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { useI18n } from '@/shared/i18n'
import { ListPageHeader } from '@/platform/shell/ListPageHeader'
import { CreateProjectModal } from './components/CreateProjectModal'
import { DeleteProjectModal } from './components/DeleteProjectModal'
import { EditProjectModal } from './components/EditProjectModal'
import { ProjectCard } from './components/ProjectCard'
import type { ProjectsApi } from './projects-api'
import { projectsApi } from './projects-api'
import { useProjectsPageData } from './useProjectsPageData'
import './projects.css'
import type { ProjectDTO } from '@/shared/api/generated/contracts'

export interface ProjectsPageProps {
  onSelectProject?: (projectId: string) => void
  api?: ProjectsApi
}

/**
 * 项目列表页：搜索与归档过滤、项目卡片网格，以及新建/编辑/删除对话框。
 *
 * <p>列表数据与动作在 {@link useProjectsPageData}，卡片在 {@link ProjectCard}；
 * 本组件只保留对话框开关状态与页面骨架。
 */
export function ProjectsPage({
  onSelectProject,
  api = projectsApi,
}: ProjectsPageProps) {
  const { t } = useI18n()
  const data = useProjectsPageData({ api })
  const [isCreateOpen, setIsCreateOpen] = useState(false)
  const [editingProject, setEditingProject] = useState<ProjectDTO | null>(null)
  const [deletingProject, setDeletingProject] = useState<ProjectDTO | null>(null)

  return (
    <main className="screen-body">
      <ListPageHeader
        title={t('projects.pageTitle')}
        search={{
          value: data.searchQuery,
          onChange: data.setSearchQuery,
          placeholder: t('projects.searchPlaceholder'),
          'aria-label': t('projects.searchLabel'),
        }}
        actions={(
          <Checkbox
            checked={data.includeArchived}
            onChange={data.setIncludeArchived}
            label={t('projects.includeArchived')}
          />
        )}
      />

      {data.errorMessage && (
        <div className="projects-status">
          <StateBlock tone="danger" title={data.errorMessage} />
          <Button onClick={() => void data.retryLoad()}>{t('projects.retry')}</Button>
        </div>
      )}

      {data.isLoading && data.projects.length === 0 && (
        <ResourceCardSkeleton label={t('projects.loading')} />
      )}

      {Boolean(data.searchQuery.trim()) && data.filteredProjects.length === 0 && !data.isLoading && (
        <StateBlock title={t('projects.empty.noMatch')} />
      )}

      {!(data.isLoading && data.projects.length === 0) && (
        <ResourceGrid>
          <CreateCard
            title={t('projects.create')}
            subtitle={t('projects.createCardSubtitle')}
            onClick={() => setIsCreateOpen(true)}
          />

          {data.filteredProjects.map((project) => (
            <ProjectCard
              key={project.id}
              project={project}
              onOpen={onSelectProject}
              onEdit={setEditingProject}
              onArchiveToggle={(target) => void data.handleArchiveToggle(target)}
              onDelete={setDeletingProject}
            />
          ))}
        </ResourceGrid>
      )}

      {/* Modals */}
      <CreateProjectModal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        onSuccess={(created) => {
          void data.invalidateProjectLists()
          onSelectProject?.(created.id)
        }}
        api={api}
      />

      <EditProjectModal
        isOpen={Boolean(editingProject)}
        project={editingProject}
        onClose={() => setEditingProject(null)}
        onSuccess={() => void data.invalidateProjectLists()}
        api={api}
      />

      <DeleteProjectModal
        isOpen={Boolean(deletingProject)}
        project={deletingProject}
        onClose={() => setDeletingProject(null)}
        onSuccess={() => void data.invalidateProjectLists()}
        api={api}
      />
    </main>
  )
}
