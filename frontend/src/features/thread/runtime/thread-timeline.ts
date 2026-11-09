export { buildThreadTimeline } from '@/features/thread/runtime/thread-timeline-builder'
import type { ThreadTimeline } from '@/features/thread/runtime/thread-timeline-types'
import { isThreadActiveStatus } from '@/features/thread/runtime/thread-panel/thread-status-format'
import type { HarnessThreadDTO } from '@/shared/api/generated/contracts'

/**
 * 工作状态使用 Thread 非 IDLE 活动状态以及待处理的 QUEUED 命令。
 * executionControl 不覆盖真实活动状态。
 * Realtime WebSocket 通过失效 snapshot 查询来保持 entries/commands 始终是新鲜的。
 */
export function isThreadWorking(
  thread: HarnessThreadDTO | undefined,
  timeline?: ThreadTimeline,
): boolean {
  if (isThreadActiveStatus(thread?.status)) {
    return true
  }
  return Boolean(timeline?.hasPendingInputs)
}
