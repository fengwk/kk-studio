import {
  CheckCircle2,
  LoaderCircle,
  RotateCw,
} from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { useI18n } from '@/shared/i18n'
import { interactionIdentity, interactionOwnerLabel } from '@/shared/lib/interactions'
import { ThreadLink } from '@/features/thread/runtime/ThreadLink'
import { InteractionCardBody } from '@/features/thread/runtime/interactions/InteractionCardBody'
import { InteractionOwnerSource } from '@/features/thread/runtime/interactions/InteractionOwnerSource'
import { useInteractionsController } from '@/features/thread/runtime/interactions/useInteractionsController'
import '@/features/thread/runtime/interactions/interactions-page.css'

export function InteractionsPage() {
  const { t } = useI18n()
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
  const sessionGroups = new Map<string, typeof items>()
  for (const item of items) {
    const key = item.type === 'ENVIRONMENT_WAIT' ? `root:${item.rootThreadId}` : item.sessionId
    const group = sessionGroups.get(key) ?? []
    group.push(item)
    sessionGroups.set(key, group)
  }
  const groups = Array.from(sessionGroups.entries()).map(([key, group]) => ({
    key,
    items: [...group].sort((left, right) => right.createdAt - left.createdAt),
    title: group[0]?.owner.title?.trim() || interactionOwnerLabel(group[0]!.owner, t),
    threadId: group[0]?.rootThreadId,
  }))

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
            {groups.map((group) => (
              <section className="interactions-session-group" key={group.key}>
                <header className="interactions-session-header">
                  <h2>{group.title}</h2>
                  {group.threadId ? (
                    <ThreadLink
                      threadId={group.threadId}
                      className="interactions-session-link"
                      aria-label={`${t('ai.interaction.session')}: ${group.title}`}
                    >
                      {t('ai.interaction.session')}
                    </ThreadLink>
                  ) : null}
                </header>
                <div className="interactions-session-items">
                  {group.items.map((item) => (
                    <div key={interactionIdentity(item)} className="interaction-feed-item">
                      <header className="interaction-item-header">
                        <div className="interaction-item-meta">
                          <InteractionOwnerSource item={item} />
                        </div>
                      </header>

                      <div className="interaction-item-body">
                        <InteractionCardBody
                          item={item}
                          onSuccess={item.type === 'ENVIRONMENT_WAIT'
                            ? undefined : () => removeItem(item.toolInvocationId)}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              </section>
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
