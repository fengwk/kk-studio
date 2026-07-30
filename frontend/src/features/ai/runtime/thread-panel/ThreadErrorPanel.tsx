import { X } from 'lucide-react'

/** Pi-style inline error panel: message stays in thread; error is a dismissible banner. */
export function ThreadErrorPanel({
  message,
  onDismiss,
}: {
  message: string
  onDismiss?: () => void
}) {
  if (!message.trim()) {
    return null
  }
  return (
    <div className="thread-error-panel" role="alert">
      <div className="thread-error-panel-body">
        <strong>Error</strong>
        <p>{message}</p>
      </div>
      {onDismiss ? (
        <button type="button" className="thread-error-dismiss" aria-label="关闭错误" onClick={onDismiss}>
          <X aria-hidden="true" />
        </button>
      ) : null}
    </div>
  )
}
