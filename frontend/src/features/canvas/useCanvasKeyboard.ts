import { useEffect, type RefObject } from 'react'
import type { CanvasDocumentState, CanvasTool } from '@/features/canvas/types'

type KeyboardApi = {
  state: Pick<
    CanvasDocumentState,
    'view' | 'helpOpen' | 'researchOpen' | 'activeGeneratorId' | 'addMenuOpen' | 'threadOpen'
  >
  stageElementRef: RefObject<HTMLElement | null>
  fitViewRef: RefObject<(() => void) | null>
  focusSelectionRef: RefObject<(() => void) | null>
  zoomRef: RefObject<((scale: number) => void) | null>
  setHelpOpen: (open: boolean, opener?: HTMLElement | null, restoreFocus?: boolean) => void
  setResearchOpen: (open: boolean, opener?: HTMLElement | null, restoreFocus?: boolean) => void
  closeGenerator: (restoreFocus?: boolean) => void
  closeAddMenu: (restoreFocus?: boolean) => void
  collapseThread: () => void
  clearSelection: () => void
  deleteSelection: () => void
  focusAgentPrompt: () => void
  setTool: (tool: CanvasTool, silent?: boolean) => void
  createTextNode: () => void
}

/** Editor keyboard shortcuts and focus recovery, kept out of the main controller body. */
export function useCanvasKeyboard(api: KeyboardApi) {
  const {
    state,
    stageElementRef,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    setHelpOpen,
    setResearchOpen,
    closeGenerator,
    closeAddMenu,
    collapseThread,
    clearSelection,
    deleteSelection,
    focusAgentPrompt,
    setTool,
    createTextNode,
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
        if (state.view !== 'editor') {
          return
        }
        event.preventDefault()
        if (state.helpOpen) {
          setHelpOpen(false, undefined, false)
        }
        if (state.researchOpen) {
          setResearchOpen(false, undefined, false)
        }
        focusAgentPrompt()
        return
      }

      if (event.key === 'Escape') {
        if (state.helpOpen) {
          setHelpOpen(false)
        } else if (state.researchOpen) {
          setResearchOpen(false)
        } else if (state.activeGeneratorId) {
          closeGenerator(true)
        } else if (state.addMenuOpen) {
          closeAddMenu(true)
        } else if (state.threadOpen) {
          collapseThread()
        } else if (state.view === 'editor') {
          clearSelection()
          stageElementRef.current?.focus({ preventScroll: true })
        }
        return
      }

      if (state.view !== 'editor') {
        return
      }

      const deleteKey = event.key === 'Delete' || event.key === 'Backspace'
      if (deleteKey && !isTyping) {
        event.preventDefault()
        deleteSelection()
        return
      }

      if (isTyping || isButton || state.helpOpen || state.researchOpen) {
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
      } else if (event.key.toLowerCase() === 'v') {
        event.preventDefault()
        setTool('select')
      } else if (event.key.toLowerCase() === 'h') {
        event.preventDefault()
        setTool('hand')
      } else if (event.key.toLowerCase() === 't') {
        event.preventDefault()
        createTextNode()
      }
    }

    window.addEventListener('keydown', handleKeyboard)
    return () => window.removeEventListener('keydown', handleKeyboard)
  }, [
    clearSelection,
    closeAddMenu,
    closeGenerator,
    collapseThread,
    createTextNode,
    deleteSelection,
    fitViewRef,
    focusAgentPrompt,
    focusSelectionRef,
    setHelpOpen,
    setResearchOpen,
    setTool,
    stageElementRef,
    state.activeGeneratorId,
    state.addMenuOpen,
    state.helpOpen,
    state.researchOpen,
    state.threadOpen,
    state.view,
    zoomRef,
  ])
}
