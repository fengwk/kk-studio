import { useCallback, useEffect, useRef, useState } from 'react'
import type { ThreadComposerControlMenu } from '@/features/thread/runtime/thread-panel/ThreadComposerControls'

/**
 * Composer 的两个覆盖层：命令面板（plus 菜单 / slash）与底栏设置菜单。
 *
 * 二者互斥：打开任一都先关另一个。{@link closeOverlay} 供焦点状态机在 Escape 时
 * 调用，并报告是否真的关掉了设置菜单——它是上层覆盖层，处理完 Escape 后外层不应
 * 继续 blur。interaction panel 接管（active=false）时清空设置菜单；slash 命令开始
 * 输入时也收起它，让命令表独占覆盖层。
 */
export function useComposerOverlays({
  active,
  slashMode,
}: {
  active: boolean
  slashMode: boolean
}) {
  const [plusMenuOpen, setPlusMenuOpen] = useState(false)
  const [controlMenu, setControlMenu] = useState<ThreadComposerControlMenu>(null)

  // 关闭覆盖层只改变显隐；若 control menu 优先处理则返回 true 避免外部继续 blur。
  const closeOverlay = useCallback((): boolean => {
    if (controlMenu != null) {
      setControlMenu(null)
      return true
    }
    setPlusMenuOpen(false)
    return false
  }, [controlMenu])

  /** 编辑器编辑交互：关闭命令表与底栏设置菜单。 */
  const dismissOverlays = useCallback(() => {
    setPlusMenuOpen(false)
    setControlMenu(null)
  }, [])

  /** 只关设置菜单：plus 按钮打开命令表前同步收起它，不依赖外部 mousedown。 */
  const closeControlMenu = useCallback(() => {
    setControlMenu(null)
  }, [])

  const handleLeaveRegion = useCallback(() => {
    setPlusMenuOpen(false)
    setControlMenu(null)
  }, [])

  useEffect(() => {
    if (!active) {
      setControlMenu(null)
    }
  }, [active])

  const slashModeRef = useRef(slashMode)
  useEffect(() => {
    const startedSlash = !slashModeRef.current && slashMode
    slashModeRef.current = slashMode
    if (startedSlash && controlMenu != null) {
      setControlMenu(null)
    }
  }, [controlMenu, slashMode])

  return {
    plusMenuOpen,
    setPlusMenuOpen,
    controlMenu,
    setControlMenu,
    closeControlMenu,
    closeOverlay,
    dismissOverlays,
    handleLeaveRegion,
  }
}
