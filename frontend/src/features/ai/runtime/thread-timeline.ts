export { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline-builder'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import type { ThreadTimeline } from '@/features/ai/runtime/thread-timeline-types'

/**
 * 工作状态使用派生的 Thread status/processing 以及待处理的 QUEUED 命令。
 * 只要 status 不是 IDLE，就表示 runtime 正在该 Thread 上工作。
 * Realtime WebSocket 通过失效 snapshot 查询来保持 entries/commands 始终是新鲜的。
 */
export function isThreadWorking(
  thread: HarnessThreadDTO | undefined,
  timeline?: ThreadTimeline,
): boolean {
  if (thread?.processing || (thread?.status != null && thread.status !== 'IDLE')) {
    return true
  }
  return Boolean(timeline?.hasPendingInputs)
}
