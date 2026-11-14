import type { CSSProperties } from 'react'

const MENU_MARGIN = 8
const MENU_GAP = 6
const MENU_MIN_HEIGHT = 120
const MENU_MAX_HEIGHT = 280
const MENU_MAX_WIDTH = 360

/** CSS 自定义属性以视口坐标交给菜单的可用高度与触发宽度。 */
type MenuCustomProperties = CSSProperties & {
  '--ui-select-trigger-width': string
  '--ui-select-available-height': string
}

/**
 * 菜单位于 body portal，必须用视口坐标定位，避免被滚动容器（如 modal-body）裁剪。
 *
 * <p>compact 变体靠右对齐并只暴露可用高度，普通变体按剩余空间决定展开方向与最大宽高。
 * 已挂载菜单的实测宽度优先于触发按钮宽度，保证第二次定位不会因内容不同而跳动。
 */
export function computeMenuPosition(
  trigger: HTMLElement,
  compact: boolean,
  menu?: HTMLElement | null,
): CSSProperties {
  const rect = trigger.getBoundingClientRect()
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight
  if (compact) {
    return computeCompactPosition(rect, viewportWidth, viewportHeight, menu)
  }
  return computeFloatingPosition(rect, viewportWidth, viewportHeight, menu)
}

function computeCompactPosition(
  rect: DOMRect,
  viewportWidth: number,
  viewportHeight: number,
  menu?: HTMLElement | null,
): MenuCustomProperties {
  const width = menu?.getBoundingClientRect().width ?? Math.max(rect.width, 148)
  const below = viewportHeight - rect.bottom - MENU_GAP - MENU_MARGIN
  const above = rect.top - MENU_GAP - MENU_MARGIN
  const openBelow = below >= above
  return {
    position: 'fixed',
    ...(openBelow
      ? { top: rect.bottom + MENU_GAP }
      : { bottom: viewportHeight - rect.top + MENU_GAP }),
    left: 'auto',
    right: Math.min(
      Math.max(MENU_MARGIN, viewportWidth - rect.right),
      Math.max(MENU_MARGIN, viewportWidth - MENU_MARGIN - width),
    ),
    '--ui-select-available-height': `${Math.max(0, openBelow ? below : above)}px`,
    '--ui-select-trigger-width': `${rect.width}px`,
  }
}

function computeFloatingPosition(
  rect: DOMRect,
  viewportWidth: number,
  viewportHeight: number,
  menu?: HTMLElement | null,
): CSSProperties {
  const spaceBelow = viewportHeight - rect.bottom - MENU_GAP - MENU_MARGIN
  const spaceAbove = rect.top - MENU_GAP - MENU_MARGIN
  const openBelow = spaceBelow >= spaceAbove || spaceBelow >= MENU_MIN_HEIGHT
  const vertical: CSSProperties = openBelow
    ? { top: rect.bottom + MENU_GAP }
    : { bottom: viewportHeight - rect.top + MENU_GAP }
  const maxHeight = Math.max(
    0,
    Math.min(MENU_MAX_HEIGHT, openBelow ? spaceBelow : spaceAbove),
  )
  const maxWidth = Math.max(rect.width, Math.min(MENU_MAX_WIDTH, viewportWidth - MENU_MARGIN * 2))
  const width = menu?.getBoundingClientRect().width ?? rect.width
  const maxLeft = Math.max(MENU_MARGIN, viewportWidth - MENU_MARGIN - width)
  return {
    ...vertical,
    position: 'fixed',
    left: Math.min(Math.max(rect.left, MENU_MARGIN), maxLeft),
    right: 'auto',
    minWidth: rect.width,
    maxWidth,
    maxHeight,
  }
}
