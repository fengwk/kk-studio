import { useEffect, useRef, type RefObject } from 'react'

const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * 同步弹窗的模态交互：打开时把焦点移入弹窗，Escape 关闭（进行中不响应），关闭后归还焦点。
 * 行为对齐现有弹窗，不另行引入弹窗框架。
 */
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
    function handleEscape(event: KeyboardEvent) {
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
    window.addEventListener('keydown', handleEscape, true)
    return () => window.removeEventListener('keydown', handleEscape, true)
  }, [onClose, pending])
}
