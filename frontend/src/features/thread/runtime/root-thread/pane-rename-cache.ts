import type { InfiniteData, QueryClient } from '@tanstack/react-query'
import type { ApplicationEventManager } from '@/shared/app-events/manager'
import { compareCatalogVersions } from '@/shared/lib/catalog-version'
import { queryKeys } from '@/shared/lib/query-keys'
import type { RenameTarget } from '@/features/thread/runtime/root-thread/types'
import type { SessionSummaryPage } from '@/features/thread/runtime/root-thread/usePaneHistory'
import { requestThreadSnapshotRefresh } from '@/features/thread/runtime/state/thread-snapshot-cache'
import type {
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  HarnessThreadSummaryDTO,
} from '@/shared/api/generated/contracts'

/**
 * 重命名成功后的缓存修补：只改已加载的 key，不新建查询。
 *
 * <p>Thread snapshot 本体直接采纳权威响应（含规范化的 name），但仅当响应 version 不旧于
 * 缓存；thread summary 只更新 name 字段。随后 invalidateAfterRename 按需重拉权威数据。
 */
export function patchThreadNameCache(
  queryClient: QueryClient,
  threadId: string,
  thread: HarnessThreadDTO,
): void {
  const snapshotKey = queryKeys.threads.snapshot(threadId)
  const snapshot = queryClient.getQueryData<HarnessThreadSnapshotDTO>(snapshotKey)
  if (
    snapshot != null
    && snapshot.thread.threadId === threadId
    && compareCatalogVersions(snapshot.version, thread.version) <= 0
  ) {
    queryClient.setQueryData<HarnessThreadSnapshotDTO>(
      snapshotKey,
      { ...snapshot, thread, version: thread.version, baseEntryId: null },
    )
  }
  for (const [key, data] of queryClient.getQueriesData<HarnessThreadSummaryDTO[]>({
    queryKey: ['agent-pane', 'threads'],
  })) {
    if (data == null) {
      continue
    }
    queryClient.setQueryData<HarnessThreadSummaryDTO[]>(
      key,
      data.map((item) => item.threadId === threadId ? { ...item, name: thread.name } : item),
    )
  }
}

/** 更新已加载缓存中的 Session 名称（owner session summaries 分页缓存）。 */
export function patchSessionNameCache(
  queryClient: QueryClient,
  ownerKey: string | null,
  sessionId: string,
  name: string,
): void {
  if (!ownerKey) {
    return
  }
  for (const [key, data] of queryClient.getQueriesData<InfiniteData<SessionSummaryPage>>({
    queryKey: ['agent-pane', 'sessions', ownerKey],
  })) {
    if (data == null) {
      continue
    }
    queryClient.setQueryData<InfiniteData<SessionSummaryPage>>(key, {
      ...data,
      pages: data.pages.map((page) => ({
        ...page,
        items: page.items.map((item) => (
          item.sessionId === sessionId ? { ...item, name } : item
        )),
      })),
    })
  }
}

/** 重命名成功后失效相应查询：Session 摘要、Thread 投影与 Chat 列表。 */
export async function invalidateAfterRename(
  queryClient: QueryClient,
  applicationEvents: ApplicationEventManager,
  ownerKey: string | null,
  renamed: RenameTarget,
): Promise<void> {
  await Promise.all([
    ...(ownerKey
      ? [
          queryClient.invalidateQueries({
            queryKey: ['agent-pane', 'sessions', ownerKey],
          }),
        ]
      : []),
    ...(renamed.kind === 'thread'
      ? [
          queryClient.invalidateQueries({
            queryKey: ['agent-pane', 'threads'],
          }),
          requestThreadSnapshotRefresh(queryClient, applicationEvents, renamed.id),
        ]
      : []),
    queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
  ])
}
