import { Bot, ChevronRight, Cpu, Pencil, Server, ServerCog, Trash2 } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { ResourceCard, type ResourceCardMetaRow } from '@/shared/ui/cards/ResourceCard'
import { Button } from '@/shared/ui/controls/Button'

type ResourceIcon = 'agent' | 'model' | 'provider' | 'server'

/** AI 目录的业务图标选择；共享 ResourceCard 只接收图标元素，不认识任何业务枚举。 */
const RESOURCE_ICONS: Record<ResourceIcon, typeof Bot> = {
  agent: Bot,
  model: Cpu,
  provider: ServerCog,
  server: Server,
}

/** AI 资源卡的元信息行；行契约与共享卡一致。 */
export type ResourceCardRow = ResourceCardMetaRow

/**
 * AI 目录资源卡：仅做工业务组合——把共享 ResourceCard 的表面/图标区/元信息/动作区
 * 与 AI 的“创建会话/编辑/删除”动作、本地化文案拼起来。
 * 旧实现里手写的卡片 DOM 与样式已完全由 shared/ui/cards 承担。
 */
export function ResourceCardLayout({
  icon,
  title,
  subtitle,
  rows,
  onStart,
  onEdit,
  onDelete,
  deletePending,
  editAriaLabel,
  deleteAriaLabel,
}: {
  icon: ResourceIcon
  title: string
  subtitle: string
  rows: ResourceCardRow[]
  onStart?: () => void
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
  editAriaLabel?: string
  deleteAriaLabel?: string
}) {
  const { t } = useI18n()
  const Icon = RESOURCE_ICONS[icon]

  return (
    <ResourceCard
      icon={<Icon aria-hidden="true" />}
      title={title}
      subtitle={subtitle}
      meta={rows}
      actions={
        <>
          {onStart ? (
            <Button
              size="compact"
              aria-label={`${t('ai.catalog.action.createSession')} ${title}`}
              onClick={onStart}
            >
              <ChevronRight aria-hidden="true" />
              {t('ai.catalog.action.createSession')}
            </Button>
          ) : null}
          <Button
            variant="ghost"
            size="compact"
            aria-label={editAriaLabel ?? `${t('ai.catalog.action.edit')} ${title}`}
            onClick={onEdit}
          >
            <Pencil aria-hidden="true" />
            {t('ai.catalog.action.edit')}
          </Button>
          <Button
            variant="ghost"
            size="compact"
            danger
            aria-label={deleteAriaLabel ?? `${t('ai.catalog.action.delete')} ${title}`}
            onClick={onDelete}
            disabled={deletePending}
          >
            <Trash2 aria-hidden="true" />
            {t('ai.catalog.action.delete')}
          </Button>
        </>
      }
    />
  )
}
