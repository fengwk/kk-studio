import { translate } from '@/shared/i18n'
import type { ConflictPresentation } from '@/shared/conflict/conflict-presenter'

/**
 * Durable acceptance/control/settings conflicts share this presenter. Callers retain their own
 * refresh and optional exact-retry callbacks, while reason and detail are never discarded.
 */
export function ConflictPresenter({
  conflict,
  onRefresh,
  onRetry,
  onClose,
}: {
  conflict: ConflictPresentation | null
  onRefresh: () => void
  onRetry?: () => void
  onClose: () => void
}) {
  if (conflict == null) {
    return null
  }
  return (
    <div className="thread-conflict-modal" role="alertdialog" aria-modal="true">
      <strong>{translate('shared.conflict.title')}</strong>
      <p>{translate('shared.conflict.reason', { reason: conflict.reason })}</p>
      <p>{conflict.detail}</p>
      <div className="thread-conflict-actions">
        <button type="button" className="ghost-btn" onClick={onClose}>
          {translate('shared.cancel')}
        </button>
        <button type="button" className="ghost-btn" onClick={onRefresh}>
          {translate('shared.conflict.refresh')}
        </button>
        {onRetry ? (
          <button type="button" className="btn-primary" onClick={onRetry}>
            {translate('shared.conflict.retry')}
          </button>
        ) : null}
      </div>
    </div>
  )
}
