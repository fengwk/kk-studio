import { X } from 'lucide-react'
import { useI18n } from '@/shared/i18n'

/** Pi-style inline error panel: message stays in thread; error is a dismissible banner. */
export function ThreadErrorPanel({
  message,
  onDismiss,
}: {
  message: string
  onDismiss?: () => void
}) {
  const { t } = useI18n()
  if (!message.trim()) {
    return null
  }
  return (
    <div className="thread-error-panel" role="alert">
      <div className="thread-error-panel-body">
        <strong>{t('ai.runtime.thread.error')}</strong>
        <p>{message}</p>
      </div>
      {onDismiss ? (
        <button
          type="button"
          className="thread-error-dismiss"
          aria-label={t('ai.runtime.thread.dismissError')}
          onClick={onDismiss}
        >
          <X aria-hidden="true" />
        </button>
      ) : null}
    </div>
  )
}
