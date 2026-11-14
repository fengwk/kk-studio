import { useCallback, useRef, useState, type RefObject } from 'react'
import { initialViews, type IdeViewState } from '@/features/environment/ide/ide-view-state'
import type { IdeTabLoadResult } from '@/features/environment/ide/ide-tab-state'

/**
 * 一个 tab 的 IDE 视图记录。
 *
 * <p>`viewsRef` 是操作读取的唯一事实来源，state 只用于渲染；`attempt` 让迟到的回执
 * 识别自己是哪一次操作的产物。写入在卸载后只更新 ref，不触发已卸载组件的更新。
 */
export function useIdeViewStore(mountedRef: RefObject<boolean>, initial: IdeTabLoadResult) {
  const [views, setViews] = useState<Record<string, IdeViewState>>(
    () => initialViews(initial.presentation, initial.recoveryByEnvironment),
  )
  const viewsRef = useRef(views)

  const commitViews = useCallback((next: Record<string, IdeViewState>) => {
    viewsRef.current = next
    if (mountedRef.current) {
      setViews(next)
    }
  }, [mountedRef])

  /** 只对仍是同一次 attempt 的视图落补丁；已被替换的操作结果一律丢弃。 */
  function updateView(
    environmentId: string,
    attempt: number,
    patch: Partial<IdeViewState>,
  ): IdeViewState | null {
    const current = viewsRef.current[environmentId]
    if (current == null || current.attempt !== attempt) {
      return null
    }
    const next = { ...current, ...patch }
    commitViews({ ...viewsRef.current, [environmentId]: next })
    return next
  }

  /** 开启新一代操作：attempt 递增，旧 attempt 的在途结果从此不再生效。 */
  function startAttempt(
    environmentId: string,
    seed: Omit<IdeViewState, 'environmentId' | 'attempt'>,
  ): number {
    const current = viewsRef.current[environmentId]
    const attempt = (current?.attempt ?? 0) + 1
    commitViews({
      ...viewsRef.current,
      [environmentId]: { ...seed, environmentId, attempt },
    })
    return attempt
  }

  /** 丢弃全部视图记录，不经过渲染状态（仅用于已确认全量重置的场景）。 */
  function clearViews(): void {
    viewsRef.current = {}
    setViews({})
  }

  return { views, viewsRef, commitViews, updateView, startAttempt, clearViews }
}
