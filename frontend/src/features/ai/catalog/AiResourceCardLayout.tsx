import { Bot, ChevronRight, Cpu, Pencil, Server, ServerCog, Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'
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
 * AI 目录资源卡：只做业务组合——把共享 ResourceCard 的图标/标题/元信息/动作区
 * 与 AI 的“创建会话/编辑/删除”动作、本地化文案拼起来；卡片外观全部来自 shared/ui/cards。
 */
export function ResourceCardLayout({
  icon,
  title,
  subtitle,
  badge,
  rows,
  onStart,
  onEdit,
  onDelete,
  deletePending,
  deleteDisabled = false,
  editAriaLabel,
  deleteAriaLabel,
}: {
  icon: ResourceIcon
  title: string
  subtitle: string
  badge?: ReactNode
  rows: ResourceCardRow[]
  onStart?: () => void
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
  /** 受保护资源（如系统内置）禁止删除时保持按钮可见但不可用。 */
  deleteDisabled?: boolean
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
      badge={badge}
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
            disabled={deletePending || deleteDisabled}
          >
            <Trash2 aria-hidden="true" />
            {t('ai.catalog.action.delete')}
          </Button>
        </>
      }
    />
  )
}
