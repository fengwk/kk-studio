import { useI18n } from '@/shared/i18n'
import '@/features/thread/runtime/thread-panel/thread-widgets.css'

/** 工作条，位于对话 transcript 与输入框之间。 */
export function ThreadWorkingStatus({
  active,
  label,
  animated = false,
}: {
  active: boolean
  label?: string
  animated?: boolean
}) {
  const { t } = useI18n()
  if (!active) {
    return null
  }
  return (
    <div
      className="thread-working"
      data-animated={animated ? 'true' : 'false'}
      aria-live="polite"
    >
      <span
        className={`thread-working-dot${animated ? '' : ' is-static'}`}
        style={animated ? undefined : { animation: 'none' }}
        aria-hidden="true"
      />
      <span>{label ?? t('ai.runtime.thread.working')}</span>
    </div>
  )
}
