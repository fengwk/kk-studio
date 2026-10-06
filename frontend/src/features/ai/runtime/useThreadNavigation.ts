import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

/** 一层查看路径：Thread ID 与进入前持焦的触发元素（必要视图状态，不复制执行状态）。 */
export interface ThreadViewLayer {
  threadId: string
  returnFocus: HTMLElement | null
}

interface ThreadPathState {
  root: string | null
  layers: ThreadViewLayer[]
}

/**
 * Pane 内查看路径：根面板保持挂载，逐层查看子代理，逐层返回。
 *
 * 状态转换是纯的：所有层操作都由渲染闭包 + `setState` 完成，不在 updater 内做
 * 副作用（StrictMode 双调用不会重复记录或丢失层）。根绑定变化在 render 派生为
 * 空路径，旧层一帧都不会泄漏；返回/截断的焦点恢复在 commit 后的 effect 中执行，
 * 目标不可用时回退到 pane 容器（`fallbackFocusRef`）。
 */
export function useThreadNavigation({
  rootThreadId,
  enabled,
  fallbackFocusRef,
}: {
  rootThreadId: string | null
  /** 仅根面板允许同 pane 查看子代理；只读视图或身份未确认时为 false。 */
  enabled: boolean
  fallbackFocusRef?: { current: HTMLElement | null }
}) {
  const [state, setState] = useState<ThreadPathState>(() => ({ root: rootThreadId, layers: [] }))
  const pendingFocusRef = useRef<{ element: HTMLElement | null } | null>(null)

  // render 派生：根绑定与路径所属根不一致时，路径视为空（不渲染陈旧层）。
  const layers = useMemo(
    () => (state.root === rootThreadId ? state.layers : []),
    [rootThreadId, state],
  )
  const activeThreadId = layers.length > 0 ? layers[layers.length - 1].threadId : null

  const commit = useCallback(
    (next: ThreadViewLayer[], focus: HTMLElement | null | undefined) => {
      if (focus !== undefined) {
        pendingFocusRef.current = { element: focus }
      }
      setState({ root: rootThreadId, layers: next })
    },
    [rootThreadId],
  )

  const openThread = useCallback((threadId: string) => {
    if (!enabled || !threadId) {
      return
    }
    // 指向执行根（或已在路径上）的链接是“返回”而不是新建层：截断到该层，
    // 并把焦点还给该层进入前的位置。
    if (threadId === rootThreadId) {
      if (layers.length > 0) {
        commit([], layers[0].returnFocus)
      }
      return
    }
    const existing = layers.findIndex((layer) => layer.threadId === threadId)
    if (existing >= 0) {
      commit(layers.slice(0, existing + 1), layers[existing].returnFocus)
      return
    }
    const active = typeof document === 'undefined' ? null : document.activeElement
    commit([...layers, { threadId, returnFocus: active instanceof HTMLElement ? active : null }], undefined)
  }, [commit, enabled, layers, rootThreadId])

  const goBack = useCallback(() => {
    if (layers.length === 0) {
      return
    }
    commit(layers.slice(0, -1), layers[layers.length - 1].returnFocus)
  }, [commit, layers])

  const goToRoot = useCallback(() => {
    if (layers.length === 0) {
      return
    }
    commit([], layers[0].returnFocus)
  }, [commit, layers])

  // 被覆盖的层不得持有焦点：进入/返回后若焦点仍在隐藏子树内，主动释放（浏览器中
  // display:none 会自然移走焦点，这里保证测试环境与显式 blur 也一致）。
  useEffect(() => {
    const active = typeof document === 'undefined' ? null : document.activeElement
    if (active instanceof HTMLElement && active.closest('[hidden]') != null) {
      active.blur()
    }
  })

  // 返回/截断后的焦点恢复：在 commit 之后执行，保留触发点，不可用时回退到 pane。
  useEffect(() => {
    const pending = pendingFocusRef.current
    if (pending == null) {
      return
    }
    pendingFocusRef.current = null
    if (pending.element != null && pending.element.isConnected) {
      pending.element.focus()
      return
    }
    fallbackFocusRef?.current?.focus()
  })

  return { layers, activeThreadId, openThread, goBack, goToRoot }
}
