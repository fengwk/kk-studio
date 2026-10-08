import {
  ExternalLink,
  MessageSquare,
  RotateCw,
  Workflow,
} from 'lucide-react'
import { useNavigate } from 'react-router'
import { Button } from '@/shared/ui/controls/Button'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import type { InteractionDTO } from '@/shared/api/contracts/ai-interaction'
import { projectsApi } from '@/features/projects/projects-api'
import { useI18n } from '@/shared/i18n'
import { interactionIdentity } from '@/shared/lib/interactions'
import { InteractionCardBody } from '@/features/ai/runtime/interactions/InteractionCardBody'
import { useInteractionsController } from '@/features/ai/runtime/interactions/useInteractionsController'

export function InteractionsPage() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const {
    items,
    isLoading,
    isFetchingMore,
    isError,
    error,
    hasMore,
    refresh,
    loadMore,
    removeItem,
  } = useInteractionsController()

  const handleOpenIssueSource = async (issueId: string, threadId: string) => {
    try {
      const issueDetail = await projectsApi.getIssue(issueId)
      const projectId = issueDetail.issue.projectId
      navigate(`/projects/${encodeURIComponent(projectId)}?issue=${encodeURIComponent(issueId)}&thread=${encodeURIComponent(threadId)}`)
    } catch {
      navigate('/projects')
    }
  }

  // 全局交互中心把 owner 导航到执行根：根面板统一承接审批与问卷，来源身份仍是原始调用。
  const renderOwnerSource = (item: InteractionDTO) => {
    const owner = item.owner
    if (owner.type === 'CHAT' && owner.chatId) {
      return (
        <Button
          variant="inline"
          onClick={() => navigate(`/chats/${encodeURIComponent(owner.chatId!)}?thread=${encodeURIComponent(item.rootThreadId)}`)}
          title={t('ai.interaction.openChatSource')}
        >
          <MessageSquare size={14} aria-hidden="true" />
          <span>{owner.chatTitle ?? t('ai.interaction.viewSource')}</span>
          {owner.rootThreadName ? <span>{owner.rootThreadName}</span> : null}
          <ExternalLink size={12} aria-hidden="true" />
        </Button>
      )
    }

    if (owner.type === 'ISSUE_AGENT' && owner.issueId) {
      return (
        <Button
          variant="inline"
          onClick={() => void handleOpenIssueSource(owner.issueId!, item.rootThreadId)}
          title={t('ai.interaction.openIssueSource')}
        >
          <Workflow size={14} aria-hidden="true" />
          <span>{owner.issueTitle ?? t('ai.interaction.viewSource')}</span>
          {owner.rootThreadName ? <span>{owner.rootThreadName}</span> : null}
          {owner.agentName ? (
            <span className="interaction-agent-badge">{owner.agentName}</span>
          ) : null}
          <ExternalLink size={12} aria-hidden="true" />
        </Button>
      )
    }

    return null
  }

  return (
    <div className="interactions-page-layout">
      <header className="interactions-page-header">
        <div className="interactions-header-title-row">
          <h1 className="interactions-page-title">
            {t('ai.interaction.pendingTitle')}
          </h1>
          <span className="interactions-count-badge">{items.length}</span>
        </div>
        <Button
          variant="ghost"
          disabled={isLoading || isFetchingMore}
          onClick={() => void refresh()}
        >
          <RotateCw size={14} className={isLoading ? 'animate-spin' : ''} />
          <span>{t('ai.interaction.refresh')}</span>
        </Button>
      </header>

      <main className="interactions-page-content">
        {isLoading && items.length === 0 ? (
          <StateBlock title={t('ai.common.loadingResources')} />
        ) : isError && items.length === 0 ? (
          <>
            <StateBlock tone="danger" title={error?.message || t('ai.common.operationFailed')} />
            <Button onClick={() => void refresh()}>
              {t('ai.interaction.retry')}
            </Button>
          </>
        ) : items.length === 0 ? (
          <StateBlock title={t('ai.interaction.empty')} />
        ) : (
          <div className="interactions-list">
            {isError ? (
              <>
                <StateBlock tone="danger" title={error?.message || t('ai.common.operationFailed')} />
                <div>
                  <Button onClick={() => void refresh()} disabled={isFetchingMore}>
                    {t('ai.interaction.retry')}
                  </Button>
                </div>
              </>
            ) : null}
            {items.map((item) => (
              <div key={interactionIdentity(item)} className="interaction-feed-item">
                <header className="interaction-item-header">
                  <div className="interaction-item-meta">
                    {renderOwnerSource(item)}
                  </div>
                </header>

                <div className="interaction-item-body">
                  <InteractionCardBody
                    item={item}
                    onSuccess={item.type === 'ENVIRONMENT_WAIT'
                      ? undefined : () => removeItem(item.interactionId)}
                  />
                </div>
              </div>
            ))}

            {hasMore ? (
              <div className="interactions-load-more-row">
                <Button
                  variant="ghost"
                  disabled={isFetchingMore}
                  onClick={() => void loadMore()}
                >
                  <RotateCw
                    size={14}
                    className={isFetchingMore ? 'animate-spin' : ''}
                  />
                  <span>{t('ai.interaction.loadMore')}</span>
                </Button>
              </div>
            ) : null}
          </div>
        )}
      </main>
    </div>
  )
}
