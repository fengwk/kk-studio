import { useMemo } from 'react'
import {
  buildThreadStatusModel,
  type ThreadStatusModelInput,
} from '@/features/ai/runtime/thread-panel/thread-status-format'
import { ThreadStatusSegmentRows } from '@/features/ai/runtime/thread-panel/thread-status-segments'
import { useI18n } from '@/shared/i18n'

/**
 * Footer：agent | model | usage
 *
 * Composition only: builds the stable text-only status model and forwards the resulting
 * segments to the responsive row layout. Pure formatting lives in
 * {@link buildThreadStatusModel}; layout/packing lives in {@link ThreadStatusSegmentRows}.
 */
export function ThreadStatusFooter({
  agentName,
  providerName,
  modelName,
  variantName,
  yoloEnabled,
  usage,
  contextWindow,
  onAgentClick,
  onModelClick,
  onVariantClick,
}: ThreadStatusModelInput) {
  const { t, locale } = useI18n()
  const model = useMemo(
    () => {
      void locale
      return buildThreadStatusModel({
        agentName,
        providerName,
        modelName,
        variantName,
        yoloEnabled,
        usage,
        contextWindow,
        onAgentClick,
        onModelClick,
        onVariantClick,
      })
    },
    [
      locale,
      agentName,
      providerName,
      modelName,
      variantName,
      yoloEnabled,
      usage,
      contextWindow,
      onAgentClick,
      onModelClick,
      onVariantClick,
    ],
  )

  return (
    <footer className="thread-status-footer" aria-label={t('ai.runtime.thread.status')}>
      <ThreadStatusSegmentRows segments={model.segments} />
    </footer>
  )
}