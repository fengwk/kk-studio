import { useEffect } from 'react'
import type { EnvironmentSummaryDTO } from '@/shared/api/generated/contracts'

// 回读略晚于截止点，避免与服务端在边界上竞争；这不是轮询间隔。
const STATUS_EXPIRY_RECHECK_SLACK_MS = 250

/** 最早的服务端截止点只安排一次回读，不在客户端推导环境过期。 */
export function useEnvironmentStatusRecheck(
  environments: readonly Pick<EnvironmentSummaryDTO, 'statusExpiresAt'>[],
  recheck: () => void,
) {
  let earliest: number | null = null
  for (const { statusExpiresAt } of environments) {
    if (statusExpiresAt != null && (earliest == null || statusExpiresAt < earliest)) earliest = statusExpiresAt
  }

  useEffect(() => {
    if (earliest == null) return
    const delay = Math.max(earliest - Date.now(), 0) + STATUS_EXPIRY_RECHECK_SLACK_MS
    const timer = setTimeout(recheck, delay)
    return () => clearTimeout(timer)
  }, [earliest, recheck])
}
