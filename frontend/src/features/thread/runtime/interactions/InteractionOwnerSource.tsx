import { ExternalLink } from 'lucide-react'
import { ThreadLink } from '@/features/thread/runtime/panes/ThreadLink'
import { useI18n } from '@/shared/i18n'
import type { InteractionDTO } from '@/shared/api/generated/contracts'
import '@/features/thread/runtime/interactions/interaction-owner-source.css'

/**
 * 交互待办的 subagent 来源入口：调用线程与执行根不同时，由 `ThreadLink` 复用 pane 的
 * 就地查看路径；入口只显示词条，不展示完整 threadId。
 */
export function InteractionOwnerSource({ item, showSubagentSource = true }: {
  item: InteractionDTO
  showSubagentSource?: boolean
}) {
  const { t } = useI18n()
  // 环境等待是聚合项，没有调用线程；只有真实子线程调用才补来源入口。
  const subagentThreadId = item.type !== 'ENVIRONMENT_WAIT' && item.threadId !== item.rootThreadId
    ? item.threadId
    : null
  if (!showSubagentSource || subagentThreadId == null) {
    return null
  }
  return (
    <ThreadLink
      className="interaction-subagent-source"
      threadId={subagentThreadId}
      title={t('ai.interaction.openSubagent')}
      aria-label={t('ai.interaction.openSubagent')}
    >
      <ExternalLink size={12} aria-hidden="true" />
      <span>{t('ai.interaction.subagentSource')}</span>
    </ThreadLink>
  )
}
