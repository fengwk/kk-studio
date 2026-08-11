import { useEffect, type RefObject } from 'react'
import type { CanvasView } from '@/features/canvas/types'

type KeyboardApi = {
  view: CanvasView
  stageElementRef: RefObject<HTMLElement | null>
  fitViewRef: RefObject<(() => void) | null>
  focusSelectionRef: RefObject<(() => void) | null>
  zoomRef: RefObject<((scale: number) => void) | null>
  clearSelection: () => void
  deleteSelection: () => void
  focusAgentPrompt: () => void
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
    focusAgentPrompt,
    createTextNode,
    closeOverlays,
  } = api

  useEffect(() => {
    function handleKeyboard(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      const isTyping = Boolean(
        target
        && (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) || target.isContentEditable),
      )
      const isButton = Boolean(target?.closest?.('button'))
      const modifier = event.metaKey || event.ctrlKey

      if (modifier && event.key.toLowerCase() === 'k') {
        if (view !== 'editor') {
          return
        }
        event.preventDefault()
        focusAgentPrompt()
        return
      }

      if (event.key === 'Escape') {
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
    focusAgentPrompt,
    focusSelectionRef,
    stageElementRef,
    view,
    zoomRef,
  ])
}
