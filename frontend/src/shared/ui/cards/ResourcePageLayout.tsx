import type { ReactNode } from 'react'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { Button } from '@/shared/ui/controls/Button'
import { SearchField } from '@/shared/ui/controls/SearchField'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import { useI18n } from '@/shared/i18n'

/** Shared resource-page shell for search, loading/error states, and content. */
export function ResourcePageLayout({
  search,
  onSearchChange,
  busy,
  error,
  mutationError,
  onRetry,
  hasCachedList = false,
  content,
  children,
}: {
  search: string
  onSearchChange: (value: string) => void
  busy: boolean
  error: unknown
  mutationError: Error | null
  onRetry?: () => void
  hasCachedList?: boolean
  content: ReactNode
  children?: ReactNode
}) {
  const { t } = useI18n()

  return (
    <section className="screen active">
      <nav className="subbar">
        <SearchField value={search} onChange={onSearchChange} />
      </nav>
      <div className="screen-body">
        {busy && <ResourceCardSkeleton label={t('ai.common.loadingResources')} />}
        {Boolean(error) && (
          <StateBlock
            title={toUserFacingErrorMessage(error)}
            tone="danger"
            action={
              onRetry ? (
                <Button size="compact" variant="secondary" onClick={onRetry}>
                  {t('shared.retry')}
                </Button>
              ) : undefined
            }
          />
        )}
        {mutationError && (
          <StateBlock
            title={mutationError.message || t('ai.common.operationFailed')}
            tone="danger"
          />
        )}
        {!busy && (!error || hasCachedList) && content}
      </div>
      {children}
    </section>
  )
}
