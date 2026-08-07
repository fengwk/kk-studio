import { GENERATION_PROFILES } from '@/features/canvas/data'
import type { GenerationMode } from '@/features/canvas/types'
import { useI18n } from '@/shared/i18n'

/** Thread 中已完成生成器结果摘要的纯展示组件。 */
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
