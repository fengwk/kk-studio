import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { IssueActivityDTO, IssueDetailDTO } from '@/shared/api/generated/contracts'
import type { ProjectsApi } from '../../projects-api'

/** Issue 活动时间线当前已加载的事实，以及后续窗口的读取状态。 */
export interface IssueActivityFeed {
  activities: IssueActivityDTO[]
  /** 服务端还有未加载的活动窗口。 */
  hasMore: boolean
  isLoadingMore: boolean
  loadFailed: boolean
  loadMore: () => void
}

/** 同一 Issue 上按 nextActivityCursor 追加读取的后续窗口。 */
interface LoadedTail {
  issueId: string
  /** 后续窗口的活动，按 sequence 升序且不重复。 */
  activities: IssueActivityDTO[]
  /** 下一窗口的起点；服务端没有再给游标时表示活动已取完。 */
  nextCursor: string | null
  /** 已读到的位置（最后一条已读活动的序号）：取到末尾后详情回读时从这里补取新追加的活动。 */
  readUpTo: string | null
}

interface UseIssueActivityFeedInput {
  issueId: string | null
  api: ProjectsApi
  /** 首屏详情快照：活动第一窗口与它的下一窗口游标都来自这里。 */
  detail: IssueDetailDTO | undefined
  /** 详情快照的读取时间：回读会换新的首页快照，但已取的后续窗口仍然有效。 */
  detailFetchedAt: number
}

/** 按序号拼接活动：首页快照与后续窗口可能重叠，序号既是去重依据也是时间线的 key。 */
function mergeActivities(base: IssueActivityDTO[], extra: IssueActivityDTO[]): IssueActivityDTO[] {
  if (extra.length === 0) {
    return base
  }
  const bySequence = new Map<string, IssueActivityDTO>()
  for (const activity of base) {
    bySequence.set(activity.sequence, activity)
  }
  for (const activity of extra) {
    if (!bySequence.has(activity.sequence)) {
      bySequence.set(activity.sequence, activity)
    }
  }
  return [...bySequence.values()].sort((left, right) => {
    const delta = BigInt(left.sequence) - BigInt(right.sequence)
    return delta < 0n ? -1 : delta > 0n ? 1 : 0
  })
}

/** 已读到的位置：本次游标与窗口内最后一条活动序号中的较大者。 */
function readUpToOf(cursor: string, activities: IssueActivityDTO[]): string {
  return activities.reduce(
    (readUpTo, activity) => (BigInt(activity.sequence) > BigInt(readUpTo) ? activity.sequence : readUpTo),
    cursor,
  )
}

/**
 * Issue 活动按快照窗口增量加载：首屏只展示详情带回的第一页，用户在时间线末尾显式取下一窗口。
 *
 * <p>游标就是窗口最后一条活动的序号，活动只追加且序号递增，因此同一 Issue 的回读（写操作后的重取）
 * 只替换首页快照，已取的后续窗口与在途窗口都继续有效：回读后的时间线是「新首页 + 已取窗口」按序号
 * 去重的拼接。只有切换 Issue 才作废已取窗口，旧 Issue 的在途结果既不入时间线也不改写状态。上一次读取
 * 到了末尾后，回读会让末尾的继续加载入口重新出现，用它从最后一条已读序号往后补取新追加的活动；读取
 * 失败只标记在时间线末尾，已加载事实保持不变。
 */
export function useIssueActivityFeed({
  issueId,
  api,
  detail,
  detailFetchedAt,
}: UseIssueActivityFeedInput): IssueActivityFeed {
  const [tail, setTail] = useState<LoadedTail | null>(null)
  const [isLoadingMore, setIsLoadingMore] = useState(false)
  const [loadFailed, setLoadFailed] = useState(false)
  // 切换 Issue 时作废在途请求；切回同一个 ID 也不接受上一次打开的响应。
  const pendingRequest = useRef<symbol | null>(null)

  // 只有切换 Issue 才清空已取窗口：同一 Issue 的回读不能丢掉用户已经展开的活动。
  useEffect(() => {
    pendingRequest.current = null
    setTail(null)
    setLoadFailed(false)
    setIsLoadingMore(false)
  }, [issueId])

  // 详情回读换了首页快照：已取到末尾的窗口重新给出继续加载入口，让新追加的活动仍可取到。
  // 快照对象会被结构共享复用，因此这里按读取时间而不是 detail 摘要素判断「读过一次」。
  useEffect(() => {
    setTail((current) =>
      current && current.issueId === issueId && current.nextCursor === null && current.readUpTo !== null
        ? { ...current, nextCursor: current.readUpTo }
        : current,
    )
  }, [detailFetchedAt, issueId])

  const loaded = tail && tail.issueId === issueId ? tail : null
  const nextCursor = loaded ? loaded.nextCursor : (detail?.nextActivityCursor ?? null)
  // 时间线可能已经展开很多页，只在快照或已取窗口变化时拼接排序。
  const activities = useMemo(
    () => mergeActivities(detail?.activities ?? [], loaded?.activities ?? []),
    [detail, loaded],
  )

  const loadMore = useCallback(async () => {
    if (!issueId || !nextCursor || pendingRequest.current) {
      return
    }
    const request = Symbol()
    pendingRequest.current = request
    setIsLoadingMore(true)
    setLoadFailed(false)
    try {
      const page = await api.getIssue(issueId, nextCursor)
      // 旧 Issue 的窗口不属于当前时间线：既不拼接，也不改写当前的游标与读取状态。
      if (pendingRequest.current !== request) {
        return
      }
      setTail((current) => ({
        issueId,
        activities: mergeActivities(
          current && current.issueId === issueId ? current.activities : [],
          page.activities,
        ),
        nextCursor: page.nextActivityCursor,
        readUpTo: readUpToOf(nextCursor, page.activities),
      }))
    } catch {
      // 失败留在时间线末尾说明并可重试，已加载的事实不受影响；旧 Issue 的失败不再属于当前时间线。
      if (pendingRequest.current === request) {
        setLoadFailed(true)
      }
    } finally {
      if (pendingRequest.current === request) {
        pendingRequest.current = null
        setIsLoadingMore(false)
      }
    }
  }, [issueId, api, nextCursor])

  return {
    activities,
    hasMore: Boolean(nextCursor),
    isLoadingMore,
    loadFailed,
    loadMore: () => void loadMore(),
  }
}
