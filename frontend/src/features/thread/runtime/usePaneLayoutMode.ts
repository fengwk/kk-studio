import { useEffect, useState, type RefObject } from 'react'

/**
 * Pane 容器宽度档位：< 640px 为 narrow。
 *
 * 监听目标容器自身的 resize（不依赖 window），挂载时先同步一次当前宽度，卸载时断开。
 * 无 ResizeObserver 的环境保持初始 wide。
 */
export function usePaneLayoutMode(
  containerRef: RefObject<HTMLElement | null>,
): 'wide' | 'narrow' {
  const [layoutMode, setLayoutMode] = useState<'wide' | 'narrow'>('wide')

  useEffect(() => {
    const el = containerRef.current
    if (!el || typeof ResizeObserver === 'undefined') {
      return
    }
    const updateSize = (width: number) => {
      setLayoutMode(width < 640 ? 'narrow' : 'wide')
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
  }, [containerRef])

  return layoutMode
}
