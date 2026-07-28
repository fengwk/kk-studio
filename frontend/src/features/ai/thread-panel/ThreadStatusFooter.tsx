import { useMemo } from 'react'
import {
  buildThreadStatusModel,
  type ThreadStatusModelInput,
} from '@/features/ai/thread-panel/thread-status-format'
import { ThreadStatusSegmentRows } from '@/features/ai/thread-panel/thread-status-segments'

export type {
  ThreadUsageCost,
  ThreadUsageNumber,
  ThreadUsageSummary,
} from '@/features/ai/thread-panel/thread-status-types'

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
  const model = useMemo(
    () =>
      buildThreadStatusModel({
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
      }),
    [
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
    <footer className="thread-status-footer" aria-label="会话状态">
      <ThreadStatusSegmentRows segments={model.segments} />
    </footer>
  )
}