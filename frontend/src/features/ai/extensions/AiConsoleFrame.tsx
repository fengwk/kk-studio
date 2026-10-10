import type { ReactNode } from 'react'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { SearchField } from '@/shared/ui/controls/SearchField'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { AiNavigation } from '@/features/ai/extensions/AiNavigation'
import { useI18n } from '@/shared/i18n'

export function AiConsoleFrame({
  search,
  onSearchChange,
  busy,
  error,
  mutationError,
  content,
  children,
}: {
  search: string
  onSearchChange: (value: string) => void
  busy: boolean
  error: unknown
  mutationError: Error | null
  content: ReactNode
  children?: ReactNode
}) {
  const { t } = useI18n()

  return (
    <section className="screen active">
      <nav className="subbar">
        <AiNavigation />
        <SearchField value={search} onChange={onSearchChange} />
      </nav>
      <div className="screen-body">
        {busy && <ResourceCardSkeleton label={t('ai.common.loadingResources')} />}
        {Boolean(error) && (
          <StateBlock
            title={error instanceof Error ? error.message : t('ai.common.resourceLoadFailed')}
            tone="danger"
          />
        )}
        {mutationError && (
          <StateBlock
            title={mutationError.message || t('ai.common.operationFailed')}
            tone="danger"
          />
        )}
        {!busy && !error && content}
      </div>
      {children}
    </section>
  )
}
