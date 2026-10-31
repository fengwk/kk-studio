import type { QueryClient } from '@tanstack/react-query'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/generated/contracts'
import { harnessService } from '@/shared/api/harness-service'
import { compareCatalogVersions } from '@/features/chat/chat-utils'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * 仅当缓存首尾完整且末 Entry 确为当前 head 时才提供游标：
 * - cached 存在且 threadId 匹配；
 * - cached.baseEntryId 为 null（首完整）；
 * - cached.entries 至少有一个条目；
 * - entries 末尾的 entryId 严格等于 cached.thread.headEntryId（末 Entry 确为当前 head）；
 * 任意条件不满足则返回 undefined，只能走全量读取。
 */
export function getSnapshotCursor(
  cached: HarnessThreadSnapshotDTO | undefined,
  threadId: string,
): string | undefined {
  if (!cached || cached.thread.threadId !== threadId) {
    return undefined
  }
  if (cached.baseEntryId !== null) {
    return undefined
  }
  const entries = cached.entries
  if (entries.length === 0) {
    return undefined
  }
  const lastEntry = entries[entries.length - 1]
  if (lastEntry.entryId !== cached.thread.headEntryId) {
    return undefined
  }
  return cached.thread.headEntryId
}

/**
 * Snapshot 缓存合并纯函数：
 * - incoming 是全量快照（baseEntryId === null）：
 *   - 若当前缓存 version 更新，保留当前缓存；
 *   - 否则替换，归一 baseEntryId 为 null。
 * - incoming 是增量快照（baseEntryId !== null）：
 *   - 若当前缓存不存在、Thread 不匹配或 base 不在当前链上，返回 undefined（表示缺基线，绝不存为完整快照）；
 *   - 若当前缓存 version 更新，保留当前缓存；
 *   - 否则保留到 base 的 prefix 再接 delta，按 id 幂等合并，归一 baseEntryId 为 null；
 *   - empty delta 仍更新所有当前 metadata，同 version 新 checkpoint 不能跳过。
 */
export function mergeThreadSnapshotCache(
  cached: HarnessThreadSnapshotDTO | undefined,
  incoming: HarnessThreadSnapshotDTO,
): HarnessThreadSnapshotDTO | undefined {
  // 增量快照永远不能在没有有效基线时被当作完整快照
  if (incoming.baseEntryId !== null) {
    if (!cached || cached.thread.threadId !== incoming.thread.threadId || cached.baseEntryId !== null) {
      return undefined
    }

    const versionComparison = compareCatalogVersions(cached.version, incoming.version)
    if (versionComparison > 0) {
      return cached
    }

    const baseIndex = cached.entries.findIndex((e) => e.entryId === incoming.baseEntryId)
    if (baseIndex === -1) {
      return undefined
    }

    const prefix = cached.entries.slice(0, baseIndex + 1)
    const seenIds = new Set(prefix.map((e) => e.entryId))
    const deltaEntries: HarnessSessionEntryDTO[] = []
    for (const entry of incoming.entries) {
      if (!seenIds.has(entry.entryId)) {
        seenIds.add(entry.entryId)
        deltaEntries.push(entry)
      }
    }

    return {
      ...incoming,
      baseEntryId: null,
      entries: [...prefix, ...deltaEntries],
    }
  }

  // 全量快照（baseEntryId === null）
  if (cached && cached.thread.threadId === incoming.thread.threadId) {
    const versionComparison = compareCatalogVersions(cached.version, incoming.version)
    if (versionComparison > 0) {
      return cached
    }
  }

  return {
    ...incoming,
    baseEntryId: null,
  }
}

export interface FetchThreadSnapshotOptions {
  signal?: AbortSignal
}

/**
 * 唯一的 snapshot query 执行器：
 * - 从完整缓存真实 head 请求 cursor；
 * - 响应到达时必须重新读取当时的当前缓存合并，不得用请求前捕获的副本；
 * - 缺基线时恰好再读一次 full，网络或 HTTP 异常绝不吞成 full fallback；
 * - 只返回结果，不在 queryFn 内调用 setQueryData（由 TanStack 写入返回值）。
 */
export async function fetchThreadSnapshot(
  queryClient: QueryClient,
  threadId: string,
  options?: FetchThreadSnapshotOptions,
): Promise<HarnessThreadSnapshotDTO> {
  const initialCached = queryClient.getQueryData<HarnessThreadSnapshotDTO>(
    queryKeys.threads.snapshot(threadId),
  )
  const cursor = getSnapshotCursor(initialCached, threadId)

  if (cursor != null) {
    const incoming = await harnessService.getThreadSnapshot(threadId, {
      afterEntryId: cursor,
      signal: options?.signal,
    })
    const currentCached = queryClient.getQueryData<HarnessThreadSnapshotDTO>(
      queryKeys.threads.snapshot(threadId),
    )
    const merged = mergeThreadSnapshotCache(currentCached, incoming)
    if (merged !== undefined) {
      return merged
    }
  }

  // 无 cursor 或 delta 缺基线：恰好再发起一次 full 读取
  const fullIncoming = await harnessService.getThreadSnapshot(threadId, {
    signal: options?.signal,
  })
  const currentCached = queryClient.getQueryData<HarnessThreadSnapshotDTO>(
    queryKeys.threads.snapshot(threadId),
  )
  const mergedFull = mergeThreadSnapshotCache(currentCached, fullIncoming)
  return mergedFull ?? { ...fullIncoming, baseEntryId: null }
}
