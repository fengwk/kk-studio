import type { QueryClient } from '@tanstack/react-query'
import type { ApplicationEventManager } from '@/shared/app-events/manager'
import { queryKeys } from '@/shared/lib/query-keys'
import { isBoundTarget, isDraftHistoryTarget, type PaneTarget } from '@/features/thread/runtime/agent-pane'
import type { HarnessAcceptedCommandsDTO, HarnessSessionEntryDTO, HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'
import { requestThreadSnapshotRefresh } from '@/features/thread/runtime/state/thread-snapshot-cache'

/** 验收成功/迟到结果后的失效：Thread 投影与 owner 的 Session 摘要。 */
export async function invalidateAcceptanceResult(
  queryClient: QueryClient,
  ownerKey: string | null,
  threadId: string,
  applicationEvents: ApplicationEventManager,
): Promise<void> {
  await Promise.all([
    requestThreadSnapshotRefresh(queryClient, applicationEvents, threadId),
    ...(ownerKey
      ? [
          queryClient.invalidateQueries({
            queryKey: ['agent-pane', 'sessions', ownerKey],
          }),
        ]
      : []),
  ])
}

/**
 * 验收成功后、切目标之前把权威 Thread 状态放进 snapshot 缓存。
 *
 * 新 Session 没有历史；新 Thread 沿用已验证的闭合前缀，直到 invalidate 取回
 * 权威快照。已有缓存的 Thread（重放）保持原样，不覆盖已加载的 Entry。
 */
export function seedAcceptedThreadSnapshot(
  queryClient: QueryClient,
  target: PaneTarget,
  draftHistoryEntries: HarnessSessionEntryDTO[],
  response: HarnessAcceptedCommandsDTO,
): void {
  const key = queryKeys.threads.snapshot(response.thread.threadId)
  if (queryClient.getQueryData<HarnessThreadSnapshotDTO>(key) != null) {
    return
  }
  queryClient.setQueryData<HarnessThreadSnapshotDTO>(key, {
    version: response.thread.version,
    thread: response.thread,
    baseEntryId: null,
    entries: isDraftHistoryTarget(target) ? draftHistoryEntries : [],
    queuedCommands: response.acceptedCommands,
    activeAssistantOutput: null,
    toolInvocations: [],
    generationFailures: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
  })
}

/** 确定失败后按需回读当前 pane 的投影与 Session 摘要。 */
export async function refreshPaneProjection(
  queryClient: QueryClient,
  ownerKey: string | null,
  target: PaneTarget,
  applicationEvents: ApplicationEventManager,
): Promise<void> {
  if (isBoundTarget(target)) {
    await requestThreadSnapshotRefresh(queryClient, applicationEvents, target.threadId)
  }
  if (ownerKey) {
    await queryClient.invalidateQueries({
      queryKey: ['agent-pane', 'sessions', ownerKey],
    })
  }
}
