import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { SAVE_SETTLE_MS } from '@/features/canvas/data'
import {
  canvasReducer,
  createInitialCanvasState,
  getActiveGenerator,
  getContextDescription,
} from '@/features/canvas/reducer'
import { useCanvasKeyboard } from '@/features/canvas/useCanvasKeyboard'
import type {
  AddMenuAction,
  AgentContextMode,
  CanvasTool,
  CanvasViewport,
  GenerationMode,
  LibraryFilter,
  ResearchTab,
  StageMetrics,
} from '@/features/canvas/types'

const DEFAULT_STAGE: StageMetrics = { width: 960, height: 640, dockTop: 520 }

export function useCanvasController() {
  const [state, dispatch] = useReducer(canvasReducer, undefined, createInitialCanvasState)
  const [stageMetrics, setStageMetricsState] = useState<StageMetrics>(DEFAULT_STAGE)
  const stageMetricsRef = useRef<StageMetrics>(DEFAULT_STAGE)
  const toastTimerRef = useRef<number | null>(null)
  const saveTimerRef = useRef<number | null>(null)
  const runTimerRef = useRef<number | null>(null)
  const fitViewRef = useRef<(() => void) | null>(null)
  const focusSelectionRef = useRef<(() => void) | null>(null)
  const zoomRef = useRef<((scale: number) => void) | null>(null)
  const pendingFitRef = useRef(false)
  const stageElementRef = useRef<HTMLElement | null>(null)
  const agentPromptRef = useRef<HTMLTextAreaElement | null>(null)
  const generationPromptRef = useRef<HTMLTextAreaElement | null>(null)
  const dockAddRef = useRef<HTMLButtonElement | null>(null)
  const helpOpenerRef = useRef<HTMLElement | null>(null)
  const researchOpenerRef = useRef<HTMLElement | null>(null)
  const helpCloseRef = useRef<HTMLButtonElement | null>(null)
  const researchCloseRef = useRef<HTMLButtonElement | null>(null)
  const helpDialogRef = useRef<HTMLDialogElement | null>(null)

  const clearToastTimer = useCallback(() => {
    if (toastTimerRef.current !== null) {
      window.clearTimeout(toastTimerRef.current)
      toastTimerRef.current = null
    }
  }, [])

  const clearSaveTimer = useCallback(() => {
    if (saveTimerRef.current !== null) {
      window.clearTimeout(saveTimerRef.current)
      saveTimerRef.current = null
    }
  }, [])

  const showToast = useCallback((message: string) => {
    dispatch({ type: 'set-toast', toast: message })
    clearToastTimer()
    toastTimerRef.current = window.setTimeout(() => {
      dispatch({ type: 'set-toast', toast: null })
      toastTimerRef.current = null
    }, 2400)
  }, [clearToastTimer])

  useEffect(() => {
    if (!state.toast) {
      return clearToastTimer
    }
    clearToastTimer()
    toastTimerRef.current = window.setTimeout(() => {
      dispatch({ type: 'set-toast', toast: null })
      toastTimerRef.current = null
    }, 2400)
    return clearToastTimer
  }, [state.toast, clearToastTimer])

  useEffect(() => {
    if (state.saveState !== 'saving') {
      return clearSaveTimer
    }
    clearSaveTimer()
    saveTimerRef.current = window.setTimeout(() => {
      dispatch({ type: 'set-save-state', saveState: 'saved' })
      saveTimerRef.current = null
    }, SAVE_SETTLE_MS)
    return clearSaveTimer
  }, [state.saveState, state.nodes, state.links, clearSaveTimer])

  const stopRunTimer = useCallback(() => {
    if (runTimerRef.current !== null) {
      window.clearInterval(runTimerRef.current)
      runTimerRef.current = null
    }
  }, [])

  useEffect(() => {
    const run = state.nodes.find((node) => node.type === 'run')
    if (!run || run.type !== 'run' || run.status !== 'running') {
      stopRunTimer()
      return stopRunTimer
    }
    if (runTimerRef.current !== null) {
      return stopRunTimer
    }
    runTimerRef.current = window.setInterval(() => {
      dispatch({ type: 'tick-agent-run', stage: stageMetricsRef.current })
    }, 620)
    return stopRunTimer
  }, [state.nodes, stopRunTimer])

  useEffect(() => () => {
    clearToastTimer()
    clearSaveTimer()
    stopRunTimer()
  }, [clearToastTimer, clearSaveTimer, stopRunTimer])

  useEffect(() => {
    if (state.focusAgentPromptToken > 0) {
      agentPromptRef.current?.focus()
    }
  }, [state.focusAgentPromptToken])

  useEffect(() => {
    if (state.focusGenerationPromptToken <= 0) {
      return
    }
    // Prompt mounts with the generator panel after the token update; focus after layout.
    const id = window.requestAnimationFrame(() => {
      generationPromptRef.current?.focus()
    })
    return () => window.cancelAnimationFrame(id)
  }, [state.focusGenerationPromptToken])

  const activeGenerator = useMemo(() => getActiveGenerator(state), [state])
  const run = useMemo(() => {
    const node = state.nodes.find((item) => item.type === 'run')
    return node && node.type === 'run' ? node : undefined
  }, [state.nodes])
  const context = useMemo(() => getContextDescription(state), [state])

  const setStageMetrics = useCallback((metrics: StageMetrics) => {
    stageMetricsRef.current = metrics
    setStageMetricsState((previous) => (
      previous.width === metrics.width
      && previous.height === metrics.height
      && previous.dockTop === metrics.dockTop
        ? previous
        : metrics
    ))
  }, [])

  const requestFitView = useCallback(() => {
    pendingFitRef.current = true
    window.requestAnimationFrame(() => {
      if (fitViewRef.current) {
        pendingFitRef.current = false
        fitViewRef.current()
      }
    })
  }, [])

  const consumePendingFit = useCallback(() => {
    if (!pendingFitRef.current) {
      return
    }
    pendingFitRef.current = false
    fitViewRef.current?.()
  }, [])

  const openEditor = useCallback(() => {
    dispatch({ type: 'set-view', view: 'editor' })
    requestFitView()
  }, [requestFitView])

  const openLibrary = useCallback(() => {
    dispatch({ type: 'set-view', view: 'library' })
  }, [])

  const handleAddAction = useCallback((action: AddMenuAction) => {
    dispatch({ type: 'handle-add-action', action, stage: stageMetricsRef.current })
    // Only restore Dock "+" for deferred file/frame actions; generators focus Prompt via token.
    if (action === 'file' || action === 'frame') {
      window.requestAnimationFrame(() => dockAddRef.current?.focus())
    }
  }, [])

  const createGenerator = useCallback((mode: GenerationMode) => {
    dispatch({ type: 'create-generator', mode, stage: stageMetricsRef.current })
  }, [])

  const createTextNode = useCallback(() => {
    dispatch({ type: 'create-text-node', stage: stageMetricsRef.current })
  }, [])

  const setViewport = useCallback((viewport: CanvasViewport) => {
    dispatch({ type: 'set-viewport', viewport })
  }, [])

  const setSelection = useCallback((ids: string[]) => {
    dispatch({ type: 'set-selection', ids })
  }, [])

  const moveNodes = useCallback((updates: Array<{ id: string; x: number; y: number }>) => {
    dispatch({ type: 'move-nodes', updates })
  }, [])

  const activateGenerator = useCallback((id: string | null, focusPrompt = false) => {
    dispatch({ type: 'activate-generator', id, focusPrompt })
  }, [])

  const closeGenerator = useCallback((restoreFocus = false) => {
    const generatorId = state.activeGeneratorId
    dispatch({ type: 'activate-generator', id: null })
    if (!restoreFocus || !generatorId) {
      return
    }
    window.requestAnimationFrame(() => {
      stageElementRef.current
        ?.querySelector<HTMLElement>(`.react-flow__node[data-id="${generatorId}"] .node-generator`)
        ?.focus({ preventScroll: true })
    })
  }, [state.activeGeneratorId])

  const setGenerationPrompt = useCallback((value: string) => {
    dispatch({ type: 'set-generation-prompt', value })
    dispatch({ type: 'mark-generator-draft-from-prompt' })
  }, [])

  const commitGenerationPrompt = useCallback(() => {
    dispatch({ type: 'mark-generator-draft-from-prompt' })
  }, [])

  const toggleGenerationExpanded = useCallback(() => {
    dispatch({ type: 'toggle-generation-expanded' })
  }, [])

  const setGenerationCapability = useCallback((capability: string) => {
    dispatch({ type: 'set-generation-capability', capability })
  }, [])

  const cycleParameter = useCallback((index: number) => {
    dispatch({ type: 'cycle-parameter', index })
  }, [])

  const toggleReference = useCallback((index: number) => {
    dispatch({ type: 'toggle-reference', index })
  }, [])

  const submitGeneration = useCallback(() => {
    dispatch({ type: 'submit-generation' })
  }, [])

  const setAgentPrompt = useCallback((value: string) => {
    dispatch({ type: 'set-agent-prompt', value })
  }, [])

  const sendAgent = useCallback(() => {
    dispatch({ type: 'send-agent-message' })
  }, [])

  const toggleAddMenu = useCallback(() => {
    dispatch({ type: 'set-add-menu-open', open: !state.addMenuOpen })
  }, [state.addMenuOpen])

  const closeAddMenu = useCallback((restoreFocus = true) => {
    dispatch({ type: 'set-add-menu-open', open: false })
    if (restoreFocus) {
      window.requestAnimationFrame(() => dockAddRef.current?.focus())
    }
  }, [])

  const setAddMenuIndex = useCallback((index: number) => {
    dispatch({ type: 'set-add-menu-index', index })
  }, [])

  const setContextMode = useCallback((mode: AgentContextMode) => {
    dispatch({ type: 'set-context-mode', mode })
  }, [])

  const resetDemo = useCallback(() => {
    stopRunTimer()
    dispatch({ type: 'reset-demo' })
    requestFitView()
  }, [requestFitView, stopRunTimer])

  const collapseThread = useCallback(() => {
    dispatch({ type: 'set-thread-open', open: false })
    window.requestAnimationFrame(() => dockAddRef.current?.focus())
  }, [])

  const openThread = useCallback(() => {
    dispatch({ type: 'set-thread-open', open: true })
  }, [])

  const runAction = useCallback((action: 'pause' | 'resume' | 'retry') => {
    if (action === 'pause') {
      dispatch({ type: 'pause-agent-run' })
    } else if (action === 'resume') {
      dispatch({ type: 'resume-agent-run' })
    } else {
      dispatch({ type: 'retry-agent-run' })
    }
  }, [])

  const consumeThreadScroll = useCallback(() => {
    dispatch({ type: 'consume-thread-scroll' })
  }, [])

  const setIdea = useCallback((idea: string) => {
    dispatch({ type: 'set-idea', idea })
  }, [])

  const createFromIdea = useCallback(() => {
    dispatch({ type: 'open-editor-from-idea' })
    // Only fit when the idea is valid and the editor will open.
    if (state.idea.trim()) {
      requestFitView()
    }
  }, [requestFitView, state.idea])

  const selectTemplate = useCallback((template: string) => {
    dispatch({ type: 'set-selected-template', template })
  }, [])

  const setLibraryFilter = useCallback((filter: LibraryFilter) => {
    dispatch({ type: 'set-library-filter', filter })
  }, [])

  const setResearchOpen = useCallback((
    open: boolean,
    opener?: HTMLElement | null,
    restoreFocus = true,
  ) => {
    if (open) {
      researchOpenerRef.current = opener ?? (document.activeElement as HTMLElement | null)
      dispatch({ type: 'set-research-open', open: true })
      window.requestAnimationFrame(() => researchCloseRef.current?.focus())
      return
    }
    dispatch({ type: 'set-research-open', open: false })
    const focusTarget = restoreFocus ? researchOpenerRef.current : null
    researchOpenerRef.current = null
    window.requestAnimationFrame(() => focusTarget?.focus())
  }, [])

  const setResearchTab = useCallback((tab: ResearchTab) => {
    dispatch({ type: 'set-research-tab', tab })
  }, [])

  const setHelpOpen = useCallback((
    open: boolean,
    opener?: HTMLElement | null,
    restoreFocus = true,
  ) => {
    const dialog = helpDialogRef.current
    if (open) {
      helpOpenerRef.current = opener ?? (document.activeElement as HTMLElement | null)
      dispatch({ type: 'set-help-open', open: true })
      window.requestAnimationFrame(() => {
        if (dialog && !dialog.open) {
          dialog.showModal()
        }
        helpCloseRef.current?.focus()
      })
      return
    }
    if (dialog?.open) {
      dialog.close()
    }
    dispatch({ type: 'set-help-open', open: false })
    const focusTarget = restoreFocus ? helpOpenerRef.current : null
    helpOpenerRef.current = null
    window.requestAnimationFrame(() => focusTarget?.focus())
  }, [])

  const setTool = useCallback((tool: CanvasTool, silent = false) => {
    dispatch({ type: 'set-tool', tool, silent })
  }, [])

  const focusAgentDock = useCallback(() => {
    dispatch({ type: 'focus-agent-prompt' })
  }, [])

  const clearSelection = useCallback(() => {
    dispatch({ type: 'clear-selection' })
  }, [])

  const deleteSelection = useCallback(() => {
    dispatch({ type: 'delete-selection' })
  }, [])

  useCanvasKeyboard({
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
    focusAgentPrompt: focusAgentDock,
    setTool,
    createTextNode,
  })

  return useMemo(() => ({
    state,
    activeGenerator,
    run,
    contextCount: context.count,
    contextDescription: context.description,
    stageMetrics,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    stageElementRef,
    agentPromptRef,
    generationPromptRef,
    dockAddRef,
    helpDialogRef,
    helpCloseRef,
    researchCloseRef,
    openLibrary,
    openEditor,
    setIdea,
    createFromIdea,
    selectTemplate,
    setLibraryFilter,
    setResearchOpen,
    setResearchTab,
    setHelpOpen,
    setToast: showToast,
    setStageMetrics,
    setViewport,
    setSelection,
    moveNodes,
    activateGenerator,
    closeGenerator,
    setGenerationPrompt,
    commitGenerationPrompt,
    toggleGenerationExpanded,
    setGenerationCapability,
    cycleParameter,
    toggleReference,
    submitGeneration,
    setAgentPrompt,
    sendAgent,
    toggleAddMenu,
    closeAddMenu,
    setAddMenuIndex,
    handleAddAction,
    createGenerator,
    setContextMode,
    resetDemo,
    collapseThread,
    openThread,
    runAction,
    consumeThreadScroll,
    setTool,
    focusAgentDock,
    consumePendingFit,
    requestFitView,
  }), [
    state,
    activeGenerator,
    run,
    context.count,
    context.description,
    stageMetrics,
    openLibrary,
    openEditor,
    setIdea,
    createFromIdea,
    selectTemplate,
    setLibraryFilter,
    setResearchOpen,
    setResearchTab,
    setHelpOpen,
    showToast,
    setStageMetrics,
    setViewport,
    setSelection,
    moveNodes,
    activateGenerator,
    closeGenerator,
    setGenerationPrompt,
    commitGenerationPrompt,
    toggleGenerationExpanded,
    setGenerationCapability,
    cycleParameter,
    toggleReference,
    submitGeneration,
    setAgentPrompt,
    sendAgent,
    toggleAddMenu,
    closeAddMenu,
    setAddMenuIndex,
    handleAddAction,
    createGenerator,
    setContextMode,
    resetDemo,
    collapseThread,
    openThread,
    runAction,
    consumeThreadScroll,
    setTool,
    focusAgentDock,
    consumePendingFit,
    requestFitView,
  ])
}

export type CanvasController = ReturnType<typeof useCanvasController>
