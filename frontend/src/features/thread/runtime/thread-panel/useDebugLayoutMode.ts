import { useEffect, useState, type RefObject } from 'react'

/** 容器达到该宽度即并排展示三区，否则退化为单区 + Tab 导航。 */
const WIDE_LAYOUT_MIN_WIDTH = 1100

/**
 * Debug 视图的容器宽度判定。
 *
 * 必须测量容器而不是 window：同一 window 内多个 pane split 的宽度互不相同。
 * 没有 ResizeObserver 时保持默认宽模式，由 CSS 的响应式规则兜住。
 */
export function useDebugLayoutMode(
  containerRef: RefObject<HTMLElement | null>,
): 'wide' | 'narrow' {
  const [layoutMode, setLayoutMode] = useState<'wide' | 'narrow'>('wide')

  useEffect(() => {
    const el = containerRef.current
    if (!el || typeof ResizeObserver === 'undefined') {
      return
    }
    const updateSize = (width: number) => {
      setLayoutMode(width >= WIDE_LAYOUT_MIN_WIDTH ? 'wide' : 'narrow')
    }
    updateSize(el.clientWidth)
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) {
        updateSize(entry.contentRect.width)
      }
    })
    observer.observe(el)
    return () => observer.disconnect()
    // containerRef 是稳定的 ref 对象：列出它不改变重跑时机（仍只在挂载后测量一次）。
  }, [containerRef])

  return layoutMode
}
