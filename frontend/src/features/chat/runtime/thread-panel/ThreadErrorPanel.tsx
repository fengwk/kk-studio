import { X } from 'lucide-react'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { useI18n } from '@/shared/i18n'

/** Pi 风格的内联错误面板：消息留在 thread 中；错误展示为可关闭的横幅。 */
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
        <IconButton
          className="thread-error-dismiss"
          size="compact"
          label={t('ai.runtime.thread.dismissError')}
          onClick={onDismiss}
        >
          <X aria-hidden="true" />
        </IconButton>
      ) : null}
    </div>
  )
}
