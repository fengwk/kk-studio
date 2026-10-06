import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Pane 内查看路径：根面板保持挂载，逐层查看子代理，逐层返回。
 *
 * 路径只保存 Thread ID，不复制 Snapshot、执行状态或编辑草稿；根绑定变化（切换
 * Thread 或重绑）时整条路径失效，避免把旧路径套到新的执行根上。进入某层时记录
 * 当时的焦点，返回时恢复，使键盘用户回到触发的链接；恢复目标不可用时回退到
 * pane 容器（由调用方通过 `fallbackFocusRef` 提供）。
 */
export function useThreadNavigation({
  rootThreadId,
  enabled,
  fallbackFocusRef,
}: {
  rootThreadId: string | null
  /** 仅根面板允许同 pane 查看子代理；只读视图或未加载完成时为 false。 */
  enabled: boolean
  fallbackFocusRef?: { current: HTMLElement | null }
}) {
  const [layers, setLayers] = useState<string[]>([])
  const returnFocusRef = useRef<(HTMLElement | null)[]>([])

  // 根绑定变化即丢弃查看路径与待恢复焦点。
  useEffect(() => {
    returnFocusRef.current = []
    setLayers([])
  }, [rootThreadId])

  const openThread = useCallback((threadId: string) => {
    if (!enabled || threadId === rootThreadId || !threadId) {
      return
    }
    const active = typeof document === 'undefined' ? null : document.activeElement
    setLayers((current) => {
      // 已在路径上则截断到该层：重复进入同一子代理不会堆积重复层。
      const existing = current.indexOf(threadId)
      if (existing >= 0) {
        return current.slice(0, existing + 1)
      }
      returnFocusRef.current = [...returnFocusRef.current, active instanceof HTMLElement ? active : null]
      return [...current, threadId]
    })
  }, [enabled, rootThreadId])

  const goBack = useCallback(() => {
    setLayers((current) => {
      if (current.length === 0) {
        return current
      }
      const target = returnFocusRef.current.pop() ?? null
      // 返回后恢复触发点焦点；触发点已卸载时聚焦 pane 容器，仍留在查看上下文中。
      queueMicrotask(() => {
        if (target != null && target.isConnected) {
          target.focus()
          return
        }
        fallbackFocusRef?.current?.focus()
      })
      return current.slice(0, -1)
    })
  }, [fallbackFocusRef])

  const goToRoot = useCallback(() => {
    returnFocusRef.current = []
    setLayers([])
  }, [])

  return {
    /** 自根向下的查看层；空数组表示当前显示根面板。 */
    layers,
    /** 顶层正在查看的 Thread；未查看子代理时为 null。 */
    activeThreadId: layers.length > 0 ? layers[layers.length - 1] : null,
    openThread,
    goBack,
    goToRoot,
  }
}
