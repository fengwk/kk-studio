import { useCallback, useEffect } from 'react'
import { useLocation, useSearchParams } from 'react-router'

interface UseProjectBoardRouteInput {
  /** 收起看板上“手动打开详情”的意图。 */
  onDismissManualDetail: () => void
}

/**
 * 项目看板的 URL 状态：`issue`、`thread` 与手动打开详情弹窗的意图。
 *
 * <p>路由是唯一事实源：切换 issue、打开线程、关闭线程与关闭详情都只改 URL，
 * 侧边的“手动打开详情”不进入 URL，因此前进后退不会复活弹窗。
 *
 * <p>页面内导航先在动作里同步收起手动详情，再改参数，两次更新合并为同一次提交，
 * 不会先渲染一帧新 issue 的弹窗；路由本身的跳转（前进后退、直接改参数）由下面的 effect 兜底。
 */
export function useProjectBoardRoute({ onDismissManualDetail }: UseProjectBoardRouteInput) {
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const issueId = searchParams.get('issue')
  const threadId = searchParams.get('thread')

  useEffect(() => {
    onDismissManualDetail()
  }, [location.key, issueId, threadId, onDismissManualDetail])

  const updateParams = useCallback((mutate: (next: URLSearchParams) => void) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      mutate(next)
      return next
    })
  }, [setSearchParams])

  /** 选中 issue（null 表示回到看板，同时清掉仍在看的线程）。 */
  const selectIssue = useCallback((id: string | null) => {
    onDismissManualDetail()
    updateParams((next) => {
      if (id) {
        next.set('issue', id)
        return
      }
      next.delete('issue')
      next.delete('thread')
    })
  }, [onDismissManualDetail, updateParams])

  /** 在保留当前 issue 的前提下打开某个线程。 */
  const openThread = useCallback((nextThreadId: string) => {
    onDismissManualDetail()
    updateParams((next) => {
      if (issueId) {
        next.set('issue', issueId)
      }
      next.set('thread', nextThreadId)
    })
  }, [issueId, onDismissManualDetail, updateParams])

  /**
   * 关闭线程：issue 与 thread 一起清掉，回到看板。
   *
   * <p>只退出线程会留下一个没有线程的 issue，看板随即自动弹出详情弹窗，所以两者一并清除。
   */
  const closeThread = useCallback(() => {
    onDismissManualDetail()
    updateParams((next) => {
      next.delete('thread')
      next.delete('issue')
    })
  }, [onDismissManualDetail, updateParams])

  return { issueId, threadId, selectIssue, openThread, closeThread }
}
