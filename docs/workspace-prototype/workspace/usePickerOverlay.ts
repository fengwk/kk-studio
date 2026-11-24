import { useCallback, useEffect, useState, type RefObject } from 'react'

export function usePickerOverlay(
  open: boolean,
  setOpen: (open: boolean) => void,
  triggerRef: RefObject<HTMLButtonElement | null>,
  panelRef: RefObject<HTMLDivElement | null>,
) {
  const [portalRoot, setPortalRoot] = useState<HTMLElement | null>(null)
  const place = useCallback(() => {
    const trigger = triggerRef.current
    const panel = panelRef.current
    if (!trigger || !panel) return
    const rect = trigger.getBoundingClientRect()
    const clip = trigger.closest('.modal-body, dialog')?.getBoundingClientRect()
    if (rect.bottom <= Math.max(0, clip?.top ?? 0) || rect.top >= Math.min(window.innerHeight, clip?.bottom ?? window.innerHeight) || rect.right <= 0 || rect.left >= window.innerWidth) {
      setOpen(false)
      return
    }
    const margin = 8
    const maxHeight = Math.max(120, window.innerHeight - margin * 2)
    panel.style.minWidth = `${Math.min(rect.width, window.innerWidth - margin * 2)}px`
    panel.style.maxHeight = `${maxHeight}px`
    panel.style.visibility = 'hidden'
    const height = panel.getBoundingClientRect().height
    const below = window.innerHeight - rect.bottom - margin
    const above = rect.top - margin
    const flip = height > below && above > below
    panel.classList.toggle('x-picker-up', flip)
    panel.style.maxHeight = `${Math.max(0, flip ? above : below)}px`
    panel.style.top = `${Math.max(margin, flip ? rect.top - Math.min(height, above) : rect.bottom)}px`
    const width = panel.getBoundingClientRect().width
    panel.style.left = `${Math.max(margin, Math.min(rect.left, window.innerWidth - width - margin))}px`
    panel.style.visibility = 'visible'
  }, [panelRef, setOpen, triggerRef])

  useEffect(() => {
    if (!open) return
    const trigger = triggerRef.current
    if (!trigger) return
    const root = trigger.closest<HTMLElement>('dialog:modal, .modal-backdrop') ?? document.body
    if (root !== portalRoot) setPortalRoot(root)
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target
      if (target instanceof Node && !trigger.contains(target) && !panelRef.current?.contains(target)) setOpen(false)
    }
    const onScroll = (event: Event) => {
      if (event.target === panelRef.current) return
      place()
    }
    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', place)
    const frame = requestAnimationFrame(place)
    return () => {
      cancelAnimationFrame(frame)
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', place)
    }
  }, [open, panelRef, place, portalRoot, setOpen, triggerRef])

  return { portalRoot, place }
}
