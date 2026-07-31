type ThreadUsageNumber = number | string

interface ThreadUsageCost {
  total: ThreadUsageNumber
}

/** Stable footer input contract; callers adapt backend usage DTOs outside the panel. */
export interface ThreadUsageSummary {
  inputTokens?: ThreadUsageNumber
  outputTokens?: ThreadUsageNumber
  cacheReadTokens?: ThreadUsageNumber
  cacheWriteTokens?: ThreadUsageNumber
  cacheWriteLongTokens?: ThreadUsageNumber
  cacheEligibleRecordCount?: ThreadUsageNumber
  cacheHitRecordCount?: ThreadUsageNumber
  cacheHitRatio?: ThreadUsageNumber
  costs?: readonly ThreadUsageCost[]
}
