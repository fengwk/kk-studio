import { translate } from '@/shared/i18n'
import type { ConflictPresentation } from '@/features/ai/runtime/conflict-presenter'

/**
 * Durable acceptance/control conflicts share this presenter. Callers may keep
 * their operation-specific retry callback, but the reason is never discarded.
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
      <strong>{translate('ai.runtime.conflict.title')}</strong>
      <p>{translate('ai.runtime.conflict.reason', { reason: conflict.reason })}</p>
      <p>{conflict.detail}</p>
      <div className="thread-conflict-actions">
        <button type="button" className="ghost-btn" onClick={onClose}>
          {translate('shared.cancel')}
        </button>
        <button type="button" className="ghost-btn" onClick={onRefresh}>
          {translate('ai.runtime.conflict.refresh')}
        </button>
        {onRetry ? (
          <button type="button" className="btn-primary" onClick={onRetry}>
            {translate('ai.runtime.conflict.retry')}
          </button>
        ) : null}
      </div>
    </div>
  )
}
