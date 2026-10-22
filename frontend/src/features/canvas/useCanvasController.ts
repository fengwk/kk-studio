import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useCanvasUploadPipeline } from '@/features/canvas/canvas-upload'
import {
  enterCanvasScope,
  publishDrafts,
  useCanvasScope,
  currentCanvasSnapshot,
} from '@/features/canvas/canvas-controller-scope'
import { projectCanvasSnapshot } from '@/features/canvas/domain'
import { overlayNodeWithDraft, type CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import { useFunctionConfigSync } from '@/features/canvas/function-config'
import { useCanvasFunctionRun } from '@/features/canvas/function-run'
import { configToFunctionArgs } from '@/features/canvas/generation'
import { useCanvasTransformBatch } from '@/features/canvas/transform-batch'
import type {
  CanvasLocalState,
  CanvasNodeCallbacks,
  StageMetrics,
} from '@/features/canvas/types'
import {
  DEFAULT_CANVAS_VIEWPORT,
  hasStoredCanvasViewport,
  loadCanvasViewport,
  saveCanvasViewport,
  type StoredCanvasViewport,
} from '@/features/canvas/viewport-storage'
import { useCanvasCommandQueue } from '@/features/canvas/useCanvasCommandQueue'
import { useCanvasDraftPersistence } from '@/features/canvas/useCanvasDraftPersistence'
import { useCanvasKeyboard } from '@/features/canvas/useCanvasKeyboard'
import { useCanvasNodeActions } from '@/features/canvas/useCanvasNodeActions'
import { useCanvasNodePlacement } from '@/features/canvas/useCanvasNodePlacement'
import { useCanvasReferenceLinks } from '@/features/canvas/useCanvasReferenceLinks'
import { useCanvasTextEditor } from '@/features/canvas/useCanvasTextEditor'
import {
  getCanvas,
  listCanvasFunctions,
} from '@/shared/api/studio-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { CanvasFunctionVersionDTO } from '@/shared/api/generated/contracts'

const DEFAULT_STAGE: StageMetrics = { width: 960, height: 640, dockTop: 520 }

/**
 * 画布编辑器的组合根：持有渲染状态、权威快照与函数目录查询、视口与选区，并把草稿持久化、命令队列、节点落位、文本编辑、引用边与节点操作
 * 这些子 hook 接到同一个 {@link useCanvasScope} 作用域上。展示 = 权威快照 + 待确认操作 + 本地草稿。
 */
export function useCanvasController(initialCanvasId?: string) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<CanvasLocalState>(() => ({
    view: initialCanvasId ? 'editor' : 'library',
    canvasId: initialCanvasId ?? null,
    selectedIds: [],
    selectedLinks: [],
    positionDrafts: {},
    drafts: {},
    storageError: null,
    viewport: initialCanvasId
      ? loadCanvasViewport(initialCanvasId)
      : { ...DEFAULT_CANVAS_VIEWPORT },
    toast: null,
    addMenuOpen: false,
    addMenuIndex: 0,
    uploadProgress: {},
    commandPending: false,
    draftPersistPending: false,
    conflictMessage: null,
    textEditor: null,
  }))
  const [initialFitPending, setInitialFitPending] = useState(
    () => Boolean(initialCanvasId && !hasStoredCanvasViewport(initialCanvasId)),
  )
  const [stageMetrics, setStageMetrics] = useState(DEFAULT_STAGE)
  const fitViewRef = useRef<(() => void) | null>(null)
  const focusSelectionRef = useRef<(() => void) | null>(null)
  const zoomRef = useRef<((scale: number) => void) | null>(null)
  const stageElementRef = useRef<HTMLElement | null>(null)
  const dockAddRef = useRef<HTMLButtonElement | null>(null)
  const closeContextMenuRef = useRef<(() => void) | null>(null)
  const scope = useCanvasScope(setState, initialCanvasId ?? null)
  const { isMountedRef, canvasEpochRef, activeCanvasIdRef, queueRef, queueCanvasIdRef, draftsRef } = scope

  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
      canvasEpochRef.current += 1
      queueRef.current = null
      queueCanvasIdRef.current = null
    }
  }, [canvasEpochRef, isMountedRef, queueCanvasIdRef, queueRef])

  useEffect(() => {
    if (state.canvasId !== activeCanvasIdRef.current) {
      enterCanvasScope(scope, state.canvasId)
    }
  }, [activeCanvasIdRef, scope, state.canvasId])

  const snapshotQuery = useQuery({
    queryKey: state.canvasId ? queryKeys.studio.canvas(state.canvasId) : ['studio', 'canvas', 'none'],
    queryFn: ({ signal }) => getCanvas(state.canvasId as string, { signal }),
    enabled: state.view === 'editor' && Boolean(state.canvasId),
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.studio.canvasModels,
    queryFn: ({ signal }) => listCanvasFunctions({ signal }),
    enabled: state.view === 'editor',
  })
  const snapshot = snapshotQuery.data
  const models = modelsQuery.data

  const draftLayer = useCanvasDraftPersistence({
    scope,
    canvasId: state.canvasId,
    view: state.view,
    drafts: state.drafts,
    snapshot,
  })
  const { reloadDrafts, runDraftPersistence, ackDurableDrafts, setPositionDrafts, dismissDraft } = draftLayer

  const { executeCommands, retryRecovery } = useCanvasCommandQueue({
    scope,
    queryClient,
    canvasId: state.canvasId,
    view: state.view,
    snapshot,
    ackDurableDraftsForScope: draftLayer.ackDurableDraftsForScope,
    applyDraftAcks: draftLayer.applyDraftAcks,
  })

  useEffect(() => {
    if (!state.toast) {
      return undefined
    }
    const timer = window.setTimeout(() => {
      setState((current) => (current.toast ? { ...current, toast: null } : current))
    }, 2800)
    return () => window.clearTimeout(timer)
  }, [state.toast])

  const setToast = useCallback((toast: string) => {
    setState((current) => ({ ...current, toast }))
  }, [])

  const { scheduleFunctionConfig, flushFunctionConfig, resetPending: resetFunctionConfigDrafts } =
    useFunctionConfigSync({
      executeCommands,
      getSnapshot: () => currentCanvasSnapshot(scope, snapshot),
      getModel: (name) => models?.find((m) => m.name === name),
      onImmediateDraft: (nodeId, functionName, config) => {
        const existing = draftsRef.current[nodeId] ?? { generation: 0, updatedAt: Date.now() }
        const nextGen = (existing.generation ?? 0) + 1
        const nextDraft: CanvasNodeDraft = {
          ...existing,
          generation: nextGen,
          updatedAt: Date.now(),
          function: {
            name: functionName,
            args: configToFunctionArgs(config, models?.find((m) => m.name === functionName)),
          },
        }
        publishDrafts(scope, { ...draftsRef.current, [nodeId]: nextDraft })
        return nextGen
      },
      getDraftGeneration: (nodeId) => draftsRef.current[nodeId]?.generation,
    })
  /**
   * 解析 node 当前将被执行函数在用户所见目录中的版本。
   *
   * 纯同步、无 await：start 在点击那一刻调用，随后即使目录 re-fetch/推进也不替换已捕获版本；
   * 返回值是脱离查询缓存的新对象（调用方随后也不会被目录原地改写影响）。
   * 目录中没有对应定义时返回 null，调用方必须 0 flush / 0 POST。
   */
  const resolveFunctionVersion = useCallback((nodeId: string): CanvasFunctionVersionDTO | null => {
    const functionName = draftsRef.current[nodeId]?.function?.name
      ?? currentCanvasSnapshot(scope, snapshot)?.nodes.find((node) => node.id === nodeId)?.function?.name
    if (!functionName) {
      return null
    }
    const version = models?.find((item) => item.name === functionName)?.version
    return version ? { definitionFingerprint: version.definitionFingerprint } : null
  }, [draftsRef, models, scope, snapshot])

  const {
    startFunctionRun,
    cancelFunctionRun,
    resolveFunctionRun,
    discardPendingRun,
    localPendingErrors,
    isNodeInFlight,
  } = useCanvasFunctionRun({
    canvasId: state.canvasId,
    queryClient,
    setToast,
    flushFunctionConfig,
    resolveFunctionVersion,
  })

  useEffect(() => () => {
    resetFunctionConfigDrafts()
  }, [resetFunctionConfigDrafts])

  const { moveNodes, commitTransforms, reset: resetTransformBatch } = useCanvasTransformBatch({
    snapshot,
    executeCommands,
    setPositionDrafts,
    getDraftGeneration: (nodeId) => draftsRef.current[nodeId]?.generation,
  })

  const placement = useCanvasNodePlacement(scope, snapshot)
  const { suggestNodeAlias, reserveNodeAlias, releaseNodeAlias, clearReservedAliases, nextTransform } = placement

  const setUploadProgress = useCallback((localId: string, progress: number | null) => {
    setState((current) => {
      const uploadProgress = { ...current.uploadProgress }
      if (progress === null) {
        delete uploadProgress[localId]
      } else {
        uploadProgress[localId] = progress
      }
      return { ...current, uploadProgress }
    })
  }, [])

  const { uploadFiles, resetUploads } = useCanvasUploadPipeline({
    canvasId: state.canvasId,
    executeCommands,
    reserveNodeAlias,
    releaseNodeAlias,
    nextTransform,
    onUploadProgress: setUploadProgress,
    setToast,
  })

  /** 离开当前画布前清空所有进行中的本地编辑流水线并进入新的作用域。 */
  const leaveCanvas = useCallback((nextCanvasId: string | null) => {
    resetTransformBatch()
    resetUploads()
    resetFunctionConfigDrafts()
    clearReservedAliases()
    enterCanvasScope(scope, nextCanvasId)
  }, [clearReservedAliases, resetFunctionConfigDrafts, resetTransformBatch, resetUploads, scope])

  const openEditor = useCallback((canvasId: string) => {
    leaveCanvas(canvasId)
    reloadDrafts()
    setInitialFitPending(!hasStoredCanvasViewport(canvasId))
    setState((current) => ({
      ...current,
      view: 'editor',
      canvasId,
      selectedIds: [],
      selectedLinks: [],
      positionDrafts: {},
      drafts: {},
      storageError: null,
      viewport: loadCanvasViewport(canvasId),
      conflictMessage: null,
      textEditor: null,
    }))
  }, [leaveCanvas, reloadDrafts])

  const openLibrary = useCallback(() => {
    leaveCanvas(null)
    setInitialFitPending(false)
    setState((current) => ({
      ...current,
      view: 'library',
      canvasId: null,
      selectedIds: [],
      selectedLinks: [],
      positionDrafts: {},
      drafts: {},
      storageError: null,
      addMenuOpen: false,
      textEditor: null,
      conflictMessage: null,
    }))
  }, [leaveCanvas])

  const setViewport = useCallback((viewport: StoredCanvasViewport) => {
    setState((current) => {
      if (current.canvasId) {
        saveCanvasViewport(current.canvasId, viewport)
      }
      return { ...current, viewport }
    })
  }, [])

  const completeInitialFit = useCallback(() => {
    setInitialFitPending(false)
  }, [])

  const setSelection = useCallback((
    selectedIds: string[],
    selectedLinks: CanvasLocalState['selectedLinks'] = [],
  ) => {
    setState((current) => {
      if (sameSelection(current.selectedIds, selectedIds, current.selectedLinks, selectedLinks)) {
        return current
      }
      return { ...current, selectedIds, selectedLinks }
    })
  }, [])

  const dismissConflictMessage = useCallback(() => {
    setState((current) => ({ ...current, conflictMessage: null }))
  }, [])

  const textEditor = useCanvasTextEditor({
    scope,
    snapshot,
    drafts: state.drafts,
    textEditor: state.textEditor,
    executeCommands,
    suggestNodeAlias,
    reserveNodeAlias,
    releaseNodeAlias,
    nextTransform,
  })
  const { editTextNode, createTextNode, closeTextEditor } = textEditor

  const { createLink, deleteLink } = useCanvasReferenceLinks({
    scope,
    snapshot,
    models,
    executeCommands,
    flushFunctionConfig,
    setToast,
  })

  const nodeActions = useCanvasNodeActions({
    scope,
    snapshot,
    models,
    state,
    executeCommands,
    flushFunctionConfig,
    setToast,
    suggestNodeAlias,
    reserveNodeAlias,
    releaseNodeAlias,
    nextTransform,
    dismissDraft,
    createTextNode,
  })

  const nodeCallbacks: CanvasNodeCallbacks = useMemo(() => ({
    editTextNode,
  }), [editTextNode])

  const toggleAddMenu = useCallback(() => {
    setState((current) => ({
      ...current,
      addMenuOpen: !current.addMenuOpen,
    }))
  }, [])
  const closeAddMenu = useCallback(() => {
    setState((current) => ({ ...current, addMenuOpen: false, addMenuIndex: 0 }))
  }, [])
  const setAddMenuIndex = useCallback((addMenuIndex: number) => {
    setState((current) => ({ ...current, addMenuIndex }))
  }, [])

  useCanvasKeyboard({
    view: state.view,
    stageElementRef,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    clearSelection: () => setSelection([]),
    deleteSelection: nodeActions.deleteSelection,
    createTextNode,
    closeOverlays: () => {
      closeAddMenu()
      closeTextEditor()
      closeContextMenuRef.current?.()
    },
  })

  // 权威快照叠加草稿层投影
  const projectedSnapshot = useMemo(() => {
    if (!snapshot) {
      return null
    }
    const base = projectCanvasSnapshot(snapshot)
    return {
      ...base,
      resourceNodes: base.resourceNodes.map((node) => overlayNodeWithDraft(node, state.drafts[node.id])),
    }
  }, [snapshot, state.drafts])

  return {
    state,
    snapshot: snapshot ?? null,
    projectedSnapshot,
    stageMetrics,
    setStageMetrics,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    stageElementRef,
    dockAddRef,
    closeContextMenuRef,
    snapshotQuery,
    modelsQuery,
    models: models ?? [],
    nodeCallbacks,
    initialFitPending,
    completeInitialFit,
    openEditor,
    openLibrary,
    setToast,
    setViewport,
    setSelection,
    moveNodes,
    commitTransforms,
    createLink,
    deleteLink,
    ...textEditor,
    ...nodeActions,
    scheduleFunctionConfig,
    flushFunctionConfig,
    startFunctionRun,
    cancelFunctionRun,
    resolveFunctionRun,
    discardPendingRun,
    localPendingErrors,
    isNodeInFlight,
    uploadFiles,
    toggleAddMenu,
    closeAddMenu,
    setAddMenuIndex,
    dismissDraft,
    retryDraftPersist: runDraftPersistence,
    ackDurableDrafts,
    retryRecovery,
    dismissConflictMessage,
    restoreDeletedDraftAsNewNode: nodeActions.saveDraftAsNewNode,
  }
}

function sameSelection(
  currentIds: string[],
  nextIds: string[],
  currentLinks: CanvasLocalState['selectedLinks'],
  nextLinks: CanvasLocalState['selectedLinks'],
): boolean {
  return currentIds.length === nextIds.length
    && currentIds.every((id, index) => id === nextIds[index])
    && currentLinks.length === nextLinks.length
    && currentLinks.every((link, index) => (
      link.sourceNodeId === nextLinks[index]?.sourceNodeId
      && link.targetNodeId === nextLinks[index]?.targetNodeId
    ))
}

export type CanvasController = ReturnType<typeof useCanvasController>
