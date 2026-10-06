import { AlertCircle, CheckCircle2, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { useI18n } from '@/shared/i18n'
import './feedback.css'

export type ToastTone = 'success' | 'error'

export interface ToastProps {
  message: string
  /** 成功用 status（polite），失败用 alert（assertive）。 */
  tone?: ToastTone
  onDismiss: () => void
  /** 自动消失时间；显式关闭始终可用。 */
  duration?: number
}

/**
 * 轻量共享 Toast：portal 到 body，避免被滚动容器或 overlay 裁剪。
 * 单一实例即一条反馈，不引入全局 provider / store / 事件总线。
 */
export function Toast({ message, tone = 'success', onDismiss, duration = 4000 }: ToastProps) {
  const { t } = useI18n()
  const onDismissRef = useRef(onDismiss)
  useEffect(() => {
    onDismissRef.current = onDismiss
  }, [onDismiss])

  useEffect(() => {
    const timer = setTimeout(() => onDismissRef.current(), duration)
    return () => clearTimeout(timer)
  }, [message, tone, duration])

  return createPortal(
    <div
      className={`ui-toast ui-toast-${tone}`}
      role={tone === 'error' ? 'alert' : 'status'}
      aria-live={tone === 'error' ? 'assertive' : 'polite'}
    >
      <span className="ui-toast-icon" aria-hidden="true">
        {tone === 'error' ? <AlertCircle /> : <CheckCircle2 />}
      </span>
      <span className="ui-toast-message">{message}</span>
      <button
        type="button"
        className="ui-toast-dismiss"
        aria-label={t('shared.dismissNotification')}
        onClick={onDismiss}
      >
        <X aria-hidden="true" />
      </button>
    </div>,
    document.body,
  )
}
