import { useEffect, type RefObject } from 'react'
import type { CanvasView } from '@/features/canvas/types'
import { hasBlockingModal, isEditableKeyboardTarget } from '@/shared/ui/blocking-overlay'

type KeyboardApi = {
  view: CanvasView
  stageElementRef: RefObject<HTMLElement | null>
  fitViewRef: RefObject<(() => void) | null>
  focusSelectionRef: RefObject<(() => void) | null>
  zoomRef: RefObject<((scale: number) => void) | null>
  clearSelection: () => void
  deleteSelection: () => void
  focusThread: () => void
  createTextNode: () => void
  closeOverlays: () => void
}

/** 编辑器键盘快捷键与焦点恢复，与主 controller 主体分离。 */
export function useCanvasKeyboard(api: KeyboardApi) {
  const {
    view,
    stageElementRef,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    clearSelection,
    deleteSelection,
    focusThread,
    createTextNode,
    closeOverlays,
  } = api

  useEffect(() => {
    function handleKeyboard(event: KeyboardEvent) {
      // 任何 blocking overlay（modal/alertdialog/lightbox）存在时，Canvas 全局
      // 快捷键全部让路：不拦截、不抢事件——不只是 Escape。
      if (hasBlockingModal()) {
        return
      }
      const target = event.target as HTMLElement | null
      const isTyping = isEditableKeyboardTarget(target)
      const isButton = Boolean(target?.closest?.('button'))
      const modifier = event.metaKey || event.ctrlKey

      if (modifier && event.key.toLowerCase() === 'k') {
        if (view !== 'editor') {
          return
        }
        event.preventDefault()
        focusThread()
        return
      }

      if (event.key === 'Escape') {
        // focused Canvas 优先于 focused Pane Composer：消费 Escape，让
        // ThreadComposer 的全局焦点恢复 handler 让路，焦点留在画布舞台。
        event.preventDefault()
        closeOverlays()
        if (view === 'editor') {
          clearSelection()
          stageElementRef.current?.focus({ preventScroll: true })
        }
        return
      }

      if (view !== 'editor') {
        return
      }

      const deleteKey = event.key === 'Delete' || event.key === 'Backspace'
      if (deleteKey && !isTyping) {
        event.preventDefault()
        deleteSelection()
        return
      }

      if (isTyping || isButton) {
        return
      }

      if (event.key === '0') {
        event.preventDefault()
        fitViewRef.current?.()
      } else if (event.key === '1') {
        event.preventDefault()
        zoomRef.current?.(1)
      } else if (event.key.toLowerCase() === 'f') {
        event.preventDefault()
        focusSelectionRef.current?.()
      } else if (event.key.toLowerCase() === 't') {
        event.preventDefault()
        createTextNode()
      }
    }

    window.addEventListener('keydown', handleKeyboard)
    return () => window.removeEventListener('keydown', handleKeyboard)
  }, [
    clearSelection,
    closeOverlays,
    createTextNode,
    deleteSelection,
    fitViewRef,
    focusThread,
    focusSelectionRef,
    stageElementRef,
    view,
    zoomRef,
  ])
}
