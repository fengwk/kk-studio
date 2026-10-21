import { useI18n } from '@/shared/i18n'

/** Pi 风格的工作条，位于对话与输入框之间（statusContainer）。 */
export function ThreadWorkingStatus({ active, label }: { active: boolean; label?: string }) {
  const { t } = useI18n()
  if (!active) {
    return null
  }
  return (
    <div className="thread-working" aria-live="polite">
      <span className="thread-working-dot" aria-hidden="true" />
      <span>{label ?? t('ai.runtime.thread.working')}</span>
    </div>
  )
}
