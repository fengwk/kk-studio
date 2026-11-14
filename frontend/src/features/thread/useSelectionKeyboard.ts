import { useCallback, type KeyboardEvent, type RefObject } from 'react'
import type { SelectionPanelItem } from '@/features/thread/SelectionRow'

export interface SelectionKeyboardOptions {
  filteredItems: SelectionPanelItem[]
  activeId: string | null
  setActiveId: (id: string | null) => void
  selectionPending: boolean
  onSelect: (id: string) => void | Promise<void>
  onClose: () => void
  onCycleControl?: () => void
  searchRef: RefObject<HTMLInputElement | null>
}

/**
 * 选择面板的键盘语义：Esc 关闭、上下与翻页移动、Home/End 边界、Enter 提交，
 * 以及把 Tab 用于循环排序控件（移动后焦点回到搜索框）。
 *
 * 组合输入（IME 组合中或 keyCode 229）一律放行，否则组字过程会被当成命令。
 */
export function useSelectionKeyboard({
  filteredItems,
  activeId,
  setActiveId,
  selectionPending,
  onSelect,
  onClose,
  onCycleControl,
  searchRef,
}: SelectionKeyboardOptions) {
  const moveActive = useCallback((delta: number) => {
    if (filteredItems.length === 0) {
      return
    }
    const currentIndex = Math.max(
      0,
      filteredItems.findIndex((item) => item.id === activeId),
    )
    const nextIndex = (
      currentIndex + delta + filteredItems.length
    ) % filteredItems.length
    setActiveId(filteredItems[nextIndex]?.id ?? null)
  }, [activeId, filteredItems, setActiveId])

  const moveToBoundary = useCallback((boundary: 'first' | 'last') => {
    const next = boundary === 'first'
      ? filteredItems[0]
      : filteredItems.at(-1)
    setActiveId(next?.id ?? null)
  }, [filteredItems, setActiveId])

  const selectActive = useCallback(() => {
    if (selectionPending || activeId == null) {
      return
    }
    void onSelect(activeId)
  }, [activeId, onSelect, selectionPending])

  return useCallback((event: KeyboardEvent<HTMLElement>) => {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onClose()
      return
    }
    if (event.key === 'Tab' && onCycleControl) {
      event.preventDefault()
      event.stopPropagation()
      onCycleControl()
      searchRef.current?.focus({ preventScroll: true })
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      event.stopPropagation()
      moveActive(event.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (event.key === 'PageDown' || event.key === 'PageUp') {
      event.preventDefault()
      event.stopPropagation()
      moveActive(event.key === 'PageDown' ? 8 : -8)
      return
    }
    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      event.stopPropagation()
      moveToBoundary(event.key === 'Home' ? 'first' : 'last')
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      event.stopPropagation()
      selectActive()
    }
  }, [moveActive, moveToBoundary, onClose, onCycleControl, searchRef, selectActive])
}
