import { useEffect, useRef, type RefObject } from 'react'

const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** 弹窗内处理 Escape 和焦点，关闭后归还焦点；进行中的操作不可关闭。 */
export function useModalDismiss(
  cardRef: RefObject<HTMLElement | null>,
  pending: boolean,
  onClose: () => void,
) {
  const previousFocusRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    previousFocusRef.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    const card = cardRef.current
    const focusTarget = card?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR) ?? card ?? null
    focusTarget?.focus({ preventScroll: true })
    return () => {
      const previous = previousFocusRef.current
      if (previous?.isConnected) {
        previous.focus({ preventScroll: true })
      }
    }
  }, [cardRef])

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Tab') {
        const card = cardRef.current
        const elements = card?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)
        const first = elements?.[0]
        const last = elements?.[elements.length - 1]
        if (!first || !last) {
          event.preventDefault()
          card?.focus()
        } else if (event.shiftKey && (document.activeElement === first || document.activeElement === card)) {
          event.preventDefault()
          last.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first.focus()
        }
        return
      }
      if (
        event.key !== 'Escape'
        || event.defaultPrevented
        || event.isComposing
        || event.keyCode === 229
      ) {
        return
      }
      event.preventDefault()
      event.stopPropagation()
      if (!pending) {
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown, true)
    return () => window.removeEventListener('keydown', handleKeyDown, true)
  }, [cardRef, onClose, pending])
}
