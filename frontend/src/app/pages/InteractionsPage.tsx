import {
  CheckCircle2,
  ExternalLink,
  LoaderCircle,
  RotateCw,
} from 'lucide-react'
import { useNavigate } from 'react-router'
import { Button } from '@/shared/ui/controls/Button'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import type { InteractionDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { interactionIdentity } from '@/shared/lib/interactions'
import { InteractionCardBody } from '@/features/thread/runtime/interactions/InteractionCardBody'
import { useInteractionsController } from '@/features/thread/runtime/interactions/useInteractionsController'
import '@/features/thread/runtime/interactions/interactions-page.css'

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

  // 全局交互中心把 owner 导航到执行根：根面板统一承接审批与问卷，来源身份仍是原始调用。
  const renderOwnerSource = (item: InteractionDTO) => {
    const owner = item.owner
    const link = owner.link
    if (link && /^\/(?!\/)/.test(link) && !link.includes('\\') && !/\p{Cc}/u.test(link)) {
      const url = new URL(link, window.location.origin)
      url.searchParams.set('thread', item.rootThreadId)
      return (
        <Button
          variant="inline"
          onClick={() => navigate(`${url.pathname}${url.search}${url.hash}`)}
          title={t('ai.interaction.viewSource')}
        >
          <span>{owner.title ?? t('ai.interaction.viewSource')}</span>
          <ExternalLink size={12} aria-hidden="true" />
        </Button>
      )
    }
    if (owner.title) {
      return (
        <span className="interaction-task-source">
          <span>{owner.title}</span>
        </span>
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
          <div className="interactions-loading-state" role="status" aria-live="polite">
            <LoaderCircle
              size={20}
              className="interactions-loading-spinner animate-spin"
              aria-hidden="true"
            />
            <span>{t('ai.common.loadingResources')}</span>
          </div>
        ) : isError && items.length === 0 ? (
          <>
            <StateBlock tone="danger" title={error?.message || t('ai.common.operationFailed')} />
            <Button onClick={() => void refresh()}>
              {t('ai.interaction.retry')}
            </Button>
          </>
        ) : items.length === 0 ? (
          <section className="interactions-empty-state" role="status">
            <div className="interactions-empty-icon" aria-hidden="true">
              <CheckCircle2 size={20} />
            </div>
            <h2 className="interactions-empty-title">{t('ai.interaction.empty')}</h2>
            <p className="interactions-empty-description">{t('ai.interaction.emptyDescription')}</p>
          </section>
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
                      ? undefined : () => removeItem(item.interactionId!)}
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
