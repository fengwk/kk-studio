import { GENERATION_PROFILES } from '@/features/canvas/data'
import type { GenerationMode } from '@/features/canvas/types'
import { useI18n } from '@/shared/i18n'

/** Pure presentation for a completed generator result summary in the thread. */
export function GenerationThreadMessage({
  mode,
  parameters,
  text,
}: {
  mode: GenerationMode
  parameters: string
  text: string
}) {
  const profile = GENERATION_PROFILES[mode]
  const { t } = useI18n()
  return (
    <div className="thread-message run generation-message">
      <strong>
        {t(profile.labelKey)}
        {' '}
        ·
        {' '}
        {parameters}
      </strong>
      <div>{text}</div>
    </div>
  )
}
