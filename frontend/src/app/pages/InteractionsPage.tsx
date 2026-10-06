import {
  AlertCircle,
  ExternalLink,
  Inbox,
  MessageSquare,
  RotateCw,
  Workflow,
} from 'lucide-react'
import { useNavigate } from 'react-router'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'
import type { InteractionDTO } from '@/shared/api/contracts/ai-interaction'
import { projectsApi } from '@/features/projects/projects-api'
import { useI18n } from '@/shared/i18n'
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
        <button
          type="button"
          className="interaction-source-link"
          onClick={() => navigate(`/chats/${encodeURIComponent(owner.chatId!)}?thread=${encodeURIComponent(item.rootThreadId)}`)}
          title={t('ai.interaction.openChatSource')}
        >
          <MessageSquare size={14} aria-hidden="true" />
          <span>{t('ai.interaction.sourceChat')}</span>
          <ExternalLink size={12} aria-hidden="true" />
        </button>
      )
    }

    if (owner.type === 'ISSUE_AGENT' && owner.issueId) {
      return (
        <button
          type="button"
          className="interaction-source-link"
          onClick={() => void handleOpenIssueSource(owner.issueId!, item.rootThreadId)}
          title={t('ai.interaction.openIssueSource')}
        >
          <Workflow size={14} aria-hidden="true" />
          <span>{t('ai.interaction.sourceIssue')}</span>
          {owner.agentName ? (
            <span className="interaction-agent-badge">{owner.agentName}</span>
          ) : null}
          <ExternalLink size={12} aria-hidden="true" />
        </button>
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
        <button
          type="button"
          className="ghost-btn interactions-refresh-btn"
          disabled={isLoading || isFetchingMore}
          onClick={() => void refresh()}
        >
          <RotateCw size={14} className={isLoading ? 'animate-spin' : ''} />
          <span>{t('ai.interaction.refresh')}</span>
        </button>
      </header>

      <main className="interactions-page-content">
        {isLoading && items.length === 0 ? (
          <div className="interactions-loading-state">
            <RotateCw size={24} className="animate-spin text-muted" />
          </div>
        ) : isError && items.length === 0 ? (
          <div className="interactions-error-state">
            <AlertCircle size={28} className="text-danger" />
            <p className="interactions-error-message">
              {error?.message || t('ai.common.operationFailed')}
            </p>
            <button
              type="button"
              className="btn-primary"
              onClick={() => void refresh()}
            >
              {t('ai.interaction.retry')}
            </button>
          </div>
        ) : items.length === 0 ? (
          <div className="interactions-empty-state">
            <Inbox size={40} className="text-muted" />
            <p className="interactions-empty-text">{t('ai.interaction.empty')}</p>
          </div>
        ) : (
          <div className="interactions-list">
            {items.map((item) => (
              <div key={item.interactionId} className="interaction-feed-item">
                <header className="interaction-item-header">
                  <div className="interaction-item-meta">
                    <span
                      className={`interaction-status-tag ${item.status.toLowerCase()}`}
                    >
                      {item.status === 'WAITING_INPUT'
                        ? t('ai.interaction.waitingInput')
                        : item.status === 'WAITING_APPROVAL'
                          ? t('ai.interaction.waitingApproval')
                          : item.status}
                    </span>
                    {renderOwnerSource(item)}
                  </div>
                  <time className="interaction-create-time">
                    {formatBackendDate(item.createTime)}
                  </time>
                </header>

                <div className="interaction-item-body">
                  <InteractionCardBody
                    item={item}
                    onSuccess={() => removeItem(item.interactionId)}
                  />
                </div>
              </div>
            ))}

            {hasMore ? (
              <div className="interactions-load-more-row">
                <button
                  type="button"
                  className="ghost-btn interactions-load-more-btn"
                  disabled={isFetchingMore}
                  onClick={() => void loadMore()}
                >
                  <RotateCw
                    size={14}
                    className={isFetchingMore ? 'animate-spin' : ''}
                  />
                  <span>{t('ai.interaction.loadMore')}</span>
                </button>
              </div>
            ) : null}
          </div>
        )}
      </main>
    </div>
  )
}
