export { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline-builder'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import type { ThreadTimeline } from '@/features/ai/runtime/thread-timeline-types'

/**
 * 工作状态使用派生的 Thread status/processing 以及待处理的 QUEUED 命令。
 * status 只描述该 Thread 自身的执行阶段：IDLE 与 STOPPED 都不在工作。
 * Realtime WebSocket 通过失效 snapshot 查询来保持 entries/commands 始终是新鲜的。
 */
export function isThreadWorking(
  thread: HarnessThreadDTO | undefined,
  timeline?: ThreadTimeline,
): boolean {
  if (thread?.processing || isBusyStatus(thread?.status)) {
    return true
  }
  return Boolean(timeline?.hasPendingInputs)
}

/** IDLE / STOPPED 是静止状态；其余阶段（排队/模型/工具/应用）都表示正在工作。 */
function isBusyStatus(status: string | null | undefined): boolean {
  return status != null && status !== 'IDLE' && status !== 'STOPPED'
}
