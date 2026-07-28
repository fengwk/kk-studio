import type { ModelUsageSummaryDTO } from '@/shared/api/contracts'
import type { ThreadUsageSummary } from '@/features/ai/thread-panel'

/**
 * Narrow backend usage DTO down to the portable ThreadUsageSummary contract the footer
 * consumes. Keeps backend types out of the portable presentation layer.
 */
export function toThreadUsageSummary(dto: ModelUsageSummaryDTO): ThreadUsageSummary {
  return {
    inputTokens: dto.inputTokens,
    outputTokens: dto.outputTokens,
    cacheReadTokens: dto.cacheReadTokens,
    cacheWriteTokens: dto.cacheWriteTokens,
    cacheWriteLongTokens: dto.cacheWriteLongTokens,
    cacheEligibleRecordCount: dto.cacheEligibleRecordCount,
    cacheHitRecordCount: dto.cacheHitRecordCount,
    cacheHitRatio: dto.cacheHitRatio,
    costs: dto.costs?.map((cost) => ({ total: cost.total })) ?? [],
  }
}
