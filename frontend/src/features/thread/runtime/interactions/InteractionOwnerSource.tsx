import { ExternalLink } from 'lucide-react'
import { useNavigate } from 'react-router'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import {
  interactionOwnerLabel,
  interactionOwnerLink,
} from '@/shared/lib/interactions'
import type { InteractionDTO } from '@/shared/api/generated/contracts'

/**
 * 交互待办的归属来源：固定显示“<类型> · <标题>”，文案与跳转都来自会话归属 SPI。
 *
 * 有产品页面的归属携带执行根 `thread` 参数跳转，因此进入的是该来源所属的执行根；
 * `AGENT_TASK` 没有产品页面，与不可信链接一样只显示文本，不伪造导航入口。
 */
export function InteractionOwnerSource({ item }: { item: InteractionDTO }) {
  const { t } = useI18n()
  const navigate = useNavigate()
  const owner = item.owner
  const label = interactionOwnerLabel(owner, t)
  const link = interactionOwnerLink(owner)
  if (link == null) {
    return <span className="interaction-task-source">{label}</span>
  }
  const url = new URL(link, window.location.origin)
  url.searchParams.set('thread', item.rootThreadId)
  return (
    <Button
      variant="inline"
      onClick={() => navigate(`${url.pathname}${url.search}${url.hash}`)}
      title={t('ai.interaction.viewSource')}
    >
      <span>{label}</span>
      <ExternalLink size={12} aria-hidden="true" />
    </Button>
  )
}
