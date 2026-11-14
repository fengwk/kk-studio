import { useLayoutEffect, useState } from 'react'
import type { CSSProperties, RefObject } from 'react'
import { computeMenuPosition } from './select-menu-position'

interface UseSelectMenuPositionInput {
  open: boolean
  compact: boolean
  triggerRef: RefObject<HTMLButtonElement | null>
  menuRef: RefObject<HTMLDivElement | null>
}

/**
 * 菜单的视口坐标：打开时先按触发按钮估算，挂载后再按实测宽度校正，
 * 并在 resize 与任意滚动容器滚动（capture）时重算，保证菜单贴着触发按钮。
 */
export function useSelectMenuPosition({
  open,
  compact,
  triggerRef,
  menuRef,
}: UseSelectMenuPositionInput): {
  menuPosition: CSSProperties | null
  placeMenu: () => void
  placeMenuBeforeOpen: () => void
} {
  const [menuPosition, setMenuPosition] = useState<CSSProperties | null>(null)

  /** 按当前触发按钮放置；菜单挂载后传入实测宽度以校正右对齐与最大宽高。 */
  const placeMenu = () => {
    if (!triggerRef.current) {
      return
    }
    setMenuPosition(computeMenuPosition(triggerRef.current, compact, menuRef.current))
  }

  /** 打开前菜单尚未挂载，只能按触发按钮估算。 */
  const placeMenuBeforeOpen = () => {
    if (!triggerRef.current) {
      return
    }
    setMenuPosition(computeMenuPosition(triggerRef.current, compact))
  }

  useLayoutEffect(() => {
    if (!open) {
      return
    }
    placeMenu()
    window.addEventListener('resize', placeMenu)
    window.addEventListener('scroll', placeMenu, true)
    return () => {
      window.removeEventListener('resize', placeMenu)
      window.removeEventListener('scroll', placeMenu, true)
    }
  }, [open, compact])

  return { menuPosition, placeMenu, placeMenuBeforeOpen }
}
