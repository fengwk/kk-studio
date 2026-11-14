import { useCallback, useEffect, useState } from 'react'
import type { RefObject } from 'react'
import type { SelectOption } from './Select'

interface UseSelectListboxInput {
  options: SelectOption[]
  selectedIndex: number
  triggerRef: RefObject<HTMLButtonElement | null>
  rootRef: RefObject<HTMLDivElement | null>
  menuRef: RefObject<HTMLDivElement | null>
  optionRefs: RefObject<Array<HTMLButtonElement | null>>
  onSelectValue: (value: string) => void
}

/**
 * 菜单的打开/关闭、活动项与文档级关闭监听。
 *
 * <p>触发按钮与 portal 菜单分处两棵子树，判断「内部」必须同时覆盖两者，
 * 否则点击菜单本身会被当成外点而立即关闭。
 */
export function useSelectListbox({
  options,
  selectedIndex,
  triggerRef,
  rootRef,
  menuRef,
  optionRefs,
  onSelectValue,
}: UseSelectListboxInput) {
  const [open, setOpen] = useState(false)
  const [modalTouchOptions, setModalTouchOptions] = useState(false)
  const [activeIndex, setActiveIndex] = useState(selectedIndex)

  const containsNode = useCallback(
    (node: Node | null): boolean => {
      if (node == null) {
        return false
      }
      return rootRef.current?.contains(node) === true || menuRef.current?.contains(node) === true
    },
    [rootRef, menuRef],
  )

  const closeListbox = useCallback(
    (focusTrigger = false) => {
      setOpen(false)
      if (focusTrigger) {
        triggerRef.current?.focus()
      }
    },
    [triggerRef],
  )

  const openListbox = () => {
    setActiveIndex(selectedIndex)
    setModalTouchOptions(Boolean(rootRef.current?.closest('.modal-touch-target')))
    setOpen(true)
  }

  const selectValue = (next: string) => {
    onSelectValue(next)
    closeListbox(true)
  }

  /** 在可用选项间循环移动；全禁用时保持原位。 */
  const moveActive = (delta: number) => {
    if (options.length === 0) {
      return
    }
    let nextIndex = activeIndex
    for (let i = 0; i < options.length; i += 1) {
      nextIndex = (nextIndex + delta + options.length) % options.length
      if (!options[nextIndex]?.disabled) {
        setActiveIndex(nextIndex)
        return
      }
    }
  }

  // 活动项变化后把焦点与视口移到该项，键盘用户无需再手动滚动。
  useEffect(() => {
    if (!open) {
      return
    }
    const option = optionRefs.current[activeIndex]
    option?.focus({ preventScroll: true })
    option?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex, open, optionRefs])

  // 打开期间的外点点击、焦点移出与 Escape 都关闭菜单。
  useEffect(() => {
    if (!open) {
      return
    }
    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target
      if (!(target instanceof Node) || !containsNode(target)) {
        closeListbox()
      }
    }
    const handleFocusIn = (event: FocusEvent) => {
      const target = event.target
      if (!(target instanceof Node) || !containsNode(target)) {
        closeListbox()
      }
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        closeListbox(true)
      }
    }
    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('focusin', handleFocusIn)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('focusin', handleFocusIn)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open, containsNode, closeListbox])

  return {
    open,
    activeIndex,
    modalTouchOptions,
    setActiveIndex,
    closeListbox,
    openListbox,
    selectValue,
    moveActive,
  }
}
