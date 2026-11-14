import { useCallback } from 'react'
import { useSearchParams } from 'react-router'

/**
 * 项目看板的 URL 状态：`issue`、`thread` 与手动打开详情弹窗的意图。
 *
 * <p>路由是唯一事实源：切换 issue、打开线程、关闭线程与关闭详情都只改 URL，
 * 侧边的“手动打开详情”不进入 URL，因此前进后退不会复活弹窗。
 */
export function useProjectBoardRoute() {
  const [searchParams, setSearchParams] = useSearchParams()
  const issueId = searchParams.get('issue')
  const threadId = searchParams.get('thread')

  const updateParams = useCallback((mutate: (next: URLSearchParams) => void) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      mutate(next)
      return next
    })
  }, [setSearchParams])

  /** 选中 issue（null 表示回到看板，同时清掉仍在看的线程）。 */
  const selectIssue = useCallback((id: string | null) => {
    updateParams((next) => {
      if (id) {
        next.set('issue', id)
        return
      }
      next.delete('issue')
      next.delete('thread')
    })
  }, [updateParams])

  /** 在保留当前 issue 的前提下打开某个线程。 */
  const openThread = useCallback((nextThreadId: string) => {
    updateParams((next) => {
      if (issueId) {
        next.set('issue', issueId)
      }
      next.set('thread', nextThreadId)
    })
  }, [issueId, updateParams])

  /**
   * 关闭线程。详情弹窗的关闭也走这里：从线程视图关闭时只退出线程，
   * 不再连带清掉 issue，避免用户失去正在看的详情。
   */
  const closeThread = useCallback(() => {
    updateParams((next) => {
      next.delete('thread')
      next.delete('issue')
    })
  }, [updateParams])

  return { issueId, threadId, selectIssue, openThread, closeThread }
}
