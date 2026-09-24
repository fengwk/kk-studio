import type { BackendDateTime } from '@/shared/api/contracts/base'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { parsePayload } from '@/features/ai/runtime/payload-json'

export interface GoalProgressRecord {
  entryId: string
  goalId: string
  status: 'complete' | 'blocked'
  reason: string
  reportedAt: string
  createTime: BackendDateTime
}

export interface BranchGoalProgressResult {
  /** 当前活跃 Goal 的最新 Agent 声明（goalId 严格匹配当前 Goal）。 */
  active: GoalProgressRecord | null
  /** 最新报告如果是针对旧 Goal（或当前已无 Goal），记录为失效的陈旧报告。 */
  stale: GoalProgressRecord | null
}

/**
 * 严格、安全解析 Entries 中的 CUSTOM goal.progress 记录。
 * 只有匹配当前目标 id 的报告才被视为活跃报告；旧 id 的报告自动归为 stale。
 */
export function parseGoalProgress(
  entries: HarnessSessionEntryDTO[],
  currentGoalId: string | null,
): BranchGoalProgressResult {
  if (!Array.isArray(entries) || entries.length === 0) {
    return { active: null, stale: null }
  }

  let active: GoalProgressRecord | null = null
  let latestStale: GoalProgressRecord | null = null

  // 倒序遍历 entries 提取最新汇报；若当前目标有活跃汇报，优先保留该活跃汇报，
  // 避免被混杂的非当前目标陈旧汇报冲掉。
  for (let i = entries.length - 1; i >= 0; i--) {
    const entry = entries[i]
    if (!entry || entry.entryType !== 'CUSTOM') {
      continue
    }
    const payload = parsePayload(entry.payloadJson)
    if (
      payload.contributorId !== 'builtin'
      || payload.customType !== 'goal.progress'
      || payload.schemaVersion !== 1
    ) {
      continue
    }
    let data: Record<string, unknown> | null = null
    if (typeof payload.dataJson === 'string') {
      try {
        data = JSON.parse(payload.dataJson)
      } catch {
        // malformed dataJson ignored
      }
    } else if (payload.data && typeof payload.data === 'object' && !Array.isArray(payload.data)) {
      data = payload.data as Record<string, unknown>
    }
    if (!data || typeof data !== 'object') {
      continue
    }
    const goalId = typeof data.goalId === 'string' ? data.goalId : null
    const status = data.status === 'complete' || data.status === 'blocked' ? data.status : null
    const reason = typeof data.reason === 'string' ? data.reason : null
    const fallbackTime = typeof entry.createTime === 'string' ? entry.createTime : ''
    const reportedAt = typeof data.reportedAt === 'string' ? data.reportedAt : fallbackTime
    if (!goalId || !status || !reason) {
      continue
    }
    const record: GoalProgressRecord = {
      entryId: entry.entryId,
      goalId,
      status,
      reason,
      reportedAt,
      createTime: entry.createTime,
    }

    if (currentGoalId != null && goalId === currentGoalId) {
      if (active == null) {
        active = record
      }
    } else {
      if (latestStale == null) {
        latestStale = record
      }
    }
  }

  if (active != null) {
    return { active, stale: null }
  }
  return { active: null, stale: latestStale }
}
