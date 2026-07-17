import { GENERATION_PROFILES } from '@/features/canvas/data'
import type { GenerationMode } from '@/features/canvas/types'

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
  return (
    <div className="thread-message run generation-message">
      <strong>
        {profile.label}
        {' '}
        ·
        {' '}
        {parameters}
      </strong>
      <div>{text}</div>
    </div>
  )
}
