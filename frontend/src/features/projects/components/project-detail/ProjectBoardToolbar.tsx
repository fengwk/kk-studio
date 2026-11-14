import { Archive, Pencil, Plus, Trash2 } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { SearchField } from '@/shared/ui/controls/SearchField'
import { Toolbar, ToolbarActions } from '@/shared/ui/layout/Toolbar'

interface ProjectBoardToolbarProps {
  searchQuery: string
  archived: boolean
  onSearchChange: (value: string) => void
  onCreateIssue: () => void
  onEditWorkflow: () => void
  onToggleArchive: () => void
  onDeleteProject: () => void
}

/**
 * 看板工具栏：搜索与四个项目级动作（新建 Issue、编辑工作流、归档/取消归档、删除）。
 *
 * <p>归档按钮的文案由当前归档状态决定，两组动作语义相同。
 */
export function ProjectBoardToolbar({
  searchQuery,
  archived,
  onSearchChange,
  onCreateIssue,
  onEditWorkflow,
  onToggleArchive,
  onDeleteProject,
}: ProjectBoardToolbarProps) {
  const { t } = useI18n()
  return (
    <Toolbar className="project-detail-toolbar">
      <SearchField
        value={searchQuery}
        onChange={onSearchChange}
        placeholder={t('projects.detail.searchPlaceholder')}
        aria-label={t('projects.detail.searchIssues')}
      />
      <ToolbarActions>
        <Button onClick={onCreateIssue}>
          <Plus size={14} aria-hidden="true" />
          <span>{t('projects.issue.create')}</span>
        </Button>
        <Button variant="ghost" onClick={onEditWorkflow}>
          <Pencil size={14} aria-hidden="true" />
          <span>{t('projects.detail.editWorkflow')}</span>
        </Button>
        <Button variant="ghost" onClick={onToggleArchive}>
          <Archive size={14} aria-hidden="true" />
          <span>{archived ? t('projects.unarchive') : t('projects.detail.archiveAction')}</span>
        </Button>
        <Button variant="ghost" danger onClick={onDeleteProject}>
          <Trash2 size={14} aria-hidden="true" />
          <span>{t('projects.detail.deleteAction')}</span>
        </Button>
      </ToolbarActions>
    </Toolbar>
  )
}
