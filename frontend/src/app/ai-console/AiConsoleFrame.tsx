import type { ReactNode } from 'react'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { Button } from '@/shared/ui/controls/Button'
import { SearchField } from '@/shared/ui/controls/SearchField'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { AiNavigation } from '@/app/ai-console/AiNavigation'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import { useI18n } from '@/shared/i18n'

/**
 * AI 目录类页面的统一外壳：导航、搜索、加载/失败状态与内容。
 *
 * `error` 是该页**主列表**读取失败的原始错误，由外壳按 status/code 转成用户可读文案；
 * `mutationError` 是调用方已经转好文案的写入失败，直接呈现。
 * 页面级失败提供重试；只有调用方确认有缓存时才保留列表。
 */
export function AiConsoleFrame({
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
        <AiNavigation />
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
