import { isRecord } from '@/shared/lib/value'
import type { ToolApprovalState } from '@/features/thread/runtime/thread-timeline-types'
import { parseEditReview } from '@/features/thread/runtime/mutation-review'

export function parseApproval(approval: unknown): ToolApprovalState | null {
  const value = approval
  if (!isRecord(value)) {
    return null
  }
  const required = value.required === true
  const decision = value.decision
  return {
    required,
    // 持久 codec 保存的是领域枚举 ALLOWED/DENIED（输入 DTO 使用的是 ALLOW/DENY）。
    decision: decision === 'ALLOWED' || decision === 'DENIED' ? decision : null,
    decisionId: typeof value.decisionId === 'string' ? value.decisionId : null,
    reason: typeof value.reason === 'string' && value.reason.trim() ? value.reason : null,
    editReview: parseEditReview(value.editReview),
  }
}
