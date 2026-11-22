import { useCallback, useEffect, useRef, useState } from 'react'
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

/** 详情快照之后按 nextActivityCursor 追加读取的窗口。 */
interface LoadedActivityWindow {
  activities: IssueActivityDTO[]
  /** 下一窗口的起点；服务端没有再给游标时表示活动已取完。 */
  nextCursor: string | null
}

interface UseIssueActivityFeedInput {
  issueId: string | null
  api: ProjectsApi
  /** 首屏详情快照：活动第一窗口与它的下一窗口游标都来自这里。 */
  detail: IssueDetailDTO | undefined
}

/**
 * Issue 活动按快照窗口增量加载：首屏只展示详情带回的第一页，用户在时间线末尾显式取下一窗口。
 *
 * <p>详情刷新（切换 Issue、写操作后回读）会作废已取的后续窗口与在途窗口：分页游标只对产生它的
 * 那份快照成立，旧基线取回的数据和失败都不能改写新快照的时间线。读取失败只标记在时间线末尾，
 * 已加载事实保持不变。
 */
export function useIssueActivityFeed({
  issueId,
  api,
  detail,
}: UseIssueActivityFeedInput): IssueActivityFeed {
  const [loaded, setLoaded] = useState<LoadedActivityWindow | null>(null)
  const [isLoadingMore, setIsLoadingMore] = useState(false)
  const [loadFailed, setLoadFailed] = useState(false)
  // 每次快照更换基线加一，在途请求据此判断自己的结果是否仍属于当前基线。
  const baselineRef = useRef(0)
  // 同一时刻只允许一个窗口在途，避免同一游标被追加两次。
  const isLoadingMoreRef = useRef(false)

  useEffect(() => {
    baselineRef.current += 1
    setLoaded((current) => (current ? null : current))
    setLoadFailed(false)
  }, [detail])

  const nextCursor = loaded ? loaded.nextCursor : (detail?.nextActivityCursor ?? null)
  const activities = [...(detail?.activities ?? []), ...(loaded?.activities ?? [])]

  const loadMore = useCallback(async () => {
    if (!issueId || !nextCursor || isLoadingMoreRef.current) {
      return
    }
    const baseline = baselineRef.current
    isLoadingMoreRef.current = true
    setIsLoadingMore(true)
    setLoadFailed(false)
    try {
      const page = await api.getIssue(issueId, nextCursor)
      if (baseline === baselineRef.current) {
        setLoaded((current) => ({
          activities: [...(current?.activities ?? []), ...page.activities],
          nextCursor: page.nextActivityCursor,
        }))
      }
    } catch {
      // 失败留在时间线末尾说明并可重试，已加载的事实不受影响；旧基线的失败不再属于当前基线。
      if (baseline === baselineRef.current) {
        setLoadFailed(true)
      }
    } finally {
      isLoadingMoreRef.current = false
      setIsLoadingMore(false)
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
