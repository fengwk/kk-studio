import { Archive, ArrowRight, FolderKanban, Pencil, Trash2 } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { formatBackendDate } from '@/shared/lib/date-format'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import type { ProjectDTO } from '@/shared/api/generated/contracts'

interface ProjectCardProps {
  project: ProjectDTO
  onOpen?: (projectId: string) => void
  onEdit: (project: ProjectDTO) => void
  onArchiveToggle: (project: ProjectDTO) => void
  onDelete: (project: ProjectDTO) => void
}

/**
 * 项目卡片：摘要信息、进入入口与编辑/归档/删除动作。
 *
 * <p>归档态只加 `is-archived` 类，动作仍然可用：归档项目要先取消归档才能继续编辑，
 * 因此不做整卡禁用。
 */
export function ProjectCard({ project, onOpen, onEdit, onArchiveToggle, onDelete }: ProjectCardProps) {
  const { t } = useI18n()
  return (
    <ResourceCard
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
        [t('projects.meta.updatedAt'), formatBackendDate(project.updatedAt)],
        [t('projects.meta.nextIssueNumber'), `#${project.nextIssueNumber}`],
      ]}
      actions={
        <>
          {onOpen && (
            <Button onClick={() => onOpen(project.id)}>
              <span>{t('projects.enter')}</span>
              <ArrowRight size={14} aria-hidden="true" />
            </Button>
          )}
          <div className="project-card-actions-right">
            <IconButton
              label={t('projects.card.edit', { title: project.title })}
              size="compact"
              onClick={() => onEdit(project)}
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
              onClick={() => onArchiveToggle(project)}
            >
              <Archive aria-hidden="true" />
            </IconButton>
            <IconButton
              label={t('projects.card.delete', { title: project.title })}
              size="compact"
              danger
              onClick={() => onDelete(project)}
            >
              <Trash2 aria-hidden="true" />
            </IconButton>
          </div>
        </>
      }
    />
  )
}
