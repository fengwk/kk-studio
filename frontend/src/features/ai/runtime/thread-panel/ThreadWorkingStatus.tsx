import { useI18n } from '@/shared/i18n'

/** Pi-style working strip between dialogue and input (statusContainer). */
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
