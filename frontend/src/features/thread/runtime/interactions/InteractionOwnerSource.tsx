import { ExternalLink } from 'lucide-react'
import { useNavigate } from 'react-router'
import { Button } from '@/shared/ui/controls/Button'
import { ThreadLink } from '@/features/thread/runtime/ThreadLink'
import { useI18n } from '@/shared/i18n'
import {
  interactionOwnerLabel,
  interactionOwnerLink,
} from '@/shared/lib/interactions'
import type { InteractionDTO } from '@/shared/api/generated/contracts'
import '@/features/thread/runtime/interactions/interaction-owner-source.css'

/**
 * 交互待办的归属来源：固定显示“<类型> · <标题>”，文案与跳转都来自会话归属 SPI。
 *
 * 有产品页面的归属携带执行根 `thread` 参数跳转，因此进入的是该来源所属的执行根；
 * `AGENT_TASK` 没有产品页面，与不可信链接一样只显示文本，不伪造导航入口。
 *
 * 调用线程与执行根不同时，在归属后补“subagent”入口：根面板由 `ThreadLink` 复用 pane 的
 * 就地查看路径，没有 pane 接管者的全局待处理页退回到 `/threads/:id`；入口只显示词条，
 * 不展示完整 threadId。
 */
export function InteractionOwnerSource({ item }: { item: InteractionDTO }) {
  const { t } = useI18n()
  const navigate = useNavigate()
  const owner = item.owner
  const label = interactionOwnerLabel(owner, t)
  const link = interactionOwnerLink(owner)
  // 环境等待是聚合项，没有调用线程；只有真实子线程调用才补来源入口。
  const subagentThreadId = item.type !== 'ENVIRONMENT_WAIT' && item.threadId !== item.rootThreadId
    ? item.threadId
    : null
  const url = link == null ? null : new URL(link, window.location.origin)
  if (url != null) {
    url.searchParams.set('thread', item.rootThreadId)
  }
  return (
    <>
      {url == null
        ? <span className="interaction-task-source">{label}</span>
        : (
          <Button
            variant="inline"
            onClick={() => navigate(`${url.pathname}${url.search}${url.hash}`)}
            title={t('ai.interaction.viewSource')}
          >
            <span>{label}</span>
            <ExternalLink size={12} aria-hidden="true" />
          </Button>
        )}
      {subagentThreadId == null ? null : (
        <ThreadLink
          className="interaction-subagent-source"
          threadId={subagentThreadId}
          title={t('ai.interaction.openSubagent')}
          aria-label={t('ai.interaction.openSubagent')}
        >
          <ExternalLink size={12} aria-hidden="true" />
          <span>{t('ai.interaction.subagentSource')}</span>
        </ThreadLink>
      )}
    </>
  )
}
