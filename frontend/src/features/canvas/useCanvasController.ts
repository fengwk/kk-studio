import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useCanvasUploadPipeline } from '@/features/canvas/canvas-upload'
import { CanvasCommandConflictError, CanvasCommandQueue } from '@/features/canvas/command-queue'
import { useCanvasVersionEvents } from '@/features/canvas/canvas-version-events'
import {
  projectCanvasSnapshot,
  type ResourceNode,
} from '@/features/canvas/domain'
import { createDefaultFunctionConfig } from '@/features/canvas/generation'
import { useFunctionConfigSync } from '@/features/canvas/function-config'
import { useCanvasFunctionRun } from '@/features/canvas/function-run'
import {
  normalizeNodeAlias,
  uniqueNodeAlias,
} from '@/features/canvas/node-alias'
import { CANVAS_SINGLE_RESOURCE_NODE_SIZE } from '@/features/canvas/resource-node-size'
import { useCanvasTransformBatch } from '@/features/canvas/transform-batch'
import type {
  AddMenuAction,
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
import { useCanvasKeyboard } from '@/features/canvas/useCanvasKeyboard'
import type {
  CanvasCommandDTO,
  CanvasTransformDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import {
  getCanvas,
  listCanvasFunctionModels,
} from '@/shared/api/studio-service'
import { queryKeys } from '@/shared/lib/query-keys'

const DEFAULT_STAGE: StageMetrics = { width: 960, height: 640, dockTop: 520 }
const NODE_SIZE: CanvasTransformDTO = {
  x: 120,
  y: 120,
  ...CANVAS_SINGLE_RESOURCE_NODE_SIZE,
}

export function useCanvasController(initialCanvasId?: UUIDString) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<CanvasLocalState>(() => ({
    view: initialCanvasId ? 'editor' : 'library',
    canvasId: initialCanvasId ?? null,
    selectedIds: [],
    selectedLinks: [],
    positionDrafts: {},
    viewport: initialCanvasId
      ? loadCanvasViewport(initialCanvasId)
      : { ...DEFAULT_CANVAS_VIEWPORT },
    toast: null,
    addMenuOpen: false,
    addMenuIndex: 0,
    threadOpen: false,
    uploadProgress: {},
    commandPending: false,
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
  /** CanvasStage 注册的右键菜单关闭回调：Canvas surface 的 Escape 关闭全部 overlay。 */
  const closeContextMenuRef = useRef<(() => void) | null>(null)
  const queueRef = useRef<CanvasCommandQueue | null>(null)
  const pendingCommandCountRef = useRef(0)
  const reservedNodeAliasesRef = useRef(new Set<string>())

  const snapshotQuery = useQuery({
    queryKey: state.canvasId ? queryKeys.studio.canvas(state.canvasId) : ['studio', 'canvas', 'none'],
    queryFn: ({ signal }) => getCanvas(state.canvasId as UUIDString, { signal }),
    enabled: state.view === 'editor' && Boolean(state.canvasId),
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.studio.canvasModels,
    queryFn: ({ signal }) => listCanvasFunctionModels({ signal }),
    enabled: state.view === 'editor',
  })

  useEffect(() => {
    if (!snapshotQuery.data || !state.canvasId) {
      return
    }
    const snapshot = snapshotQuery.data
    if (!queueRef.current) {
      const canvasId = state.canvasId
      queueRef.current = new CanvasCommandQueue(canvasId, {
        initialSnapshot: snapshot,
        onSnapshot: (next) => {
          queryClient.setQueryData(queryKeys.studio.canvas(canvasId), next)
        },
      })
    } else {
      const authoritative = queueRef.current.replaceSnapshot(snapshot)
      if (authoritative !== snapshot) {
        queryClient.setQueryData(queryKeys.studio.canvas(state.canvasId), authoritative)
      }
    }
  }, [queryClient, snapshotQuery.data, state.canvasId])

  // 应用事件 WebSocket version：按最后已知版本拉取 changes（连续 patches 或全量快照）；
  // resync 事件通过 invalidate 触发权威快照整体替换。
  const syncCanvasChanges = useCallback(() => {
    void queueRef.current?.syncFrom().catch(() => undefined)
  }, [])
  const resyncCanvas = useCallback(() => {
    const canvasId = state.canvasId
    if (!canvasId) {
      return
    }
    void queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvas(canvasId) })
  }, [queryClient, state.canvasId])
  useCanvasVersionEvents({
    canvasId: state.canvasId,
    enabled: state.view === 'editor' && snapshotQuery.isSuccess,
    version: snapshotQuery.data?.document.version ?? '0',
    onVersion: syncCanvasChanges,
    onResync: resyncCanvas,
  })

  useEffect(() => {
    if (!state.toast) {
      return
    }
    const timer = window.setTimeout(() => {
      setState((current) => ({ ...current, toast: null }))
    }, 2600)
    return () => window.clearTimeout(timer)
  }, [state.toast])

  const setToast = useCallback((toast: string) => {
    setState((current) => ({ ...current, toast }))
  }, [])

  const executeCommands = useCallback(async (commands: CanvasCommandDTO[]) => {
    const queue = queueRef.current
    if (!queue) {
      throw new Error('Canvas snapshot is not ready')
    }
    pendingCommandCountRef.current += 1
    setState((current) => ({ ...current, commandPending: true, conflictMessage: null }))
    try {
      return await queue.enqueue(commands)
    } catch (error) {
      if (error instanceof CanvasCommandConflictError) {
        setState((current) => ({
          ...current,
          conflictMessage: error.message,
          toast: '画布已在其他位置更新，请检查最新内容后重试。',
        }))
      } else {
        setState((current) => ({
          ...current,
          toast: error instanceof Error ? error.message : '画布操作失败',
        }))
      }
      throw error
    } finally {
      pendingCommandCountRef.current -= 1
      if (pendingCommandCountRef.current === 0) {
        setState((current) => ({ ...current, commandPending: false }))
      }
    }
  }, [])

  const { scheduleFunctionConfig, flushFunctionConfig, resetPending: resetFunctionConfigDrafts } =
    useFunctionConfigSync(executeCommands)
  const { startFunctionRun, cancelFunctionRun } = useCanvasFunctionRun({
    canvasId: state.canvasId,
    queryClient,
    setToast,
    flushFunctionConfig,
  })

  useEffect(() => () => {
    resetFunctionConfigDrafts()
  }, [resetFunctionConfigDrafts])

  const setPositionDrafts = useCallback((
    updater: (current: Record<string, { x: number; y: number }>) => Record<string, { x: number; y: number }>,
  ) => {
    setState((current) => ({ ...current, positionDrafts: updater(current.positionDrafts) }))
  }, [])

  const transformBatch = useCanvasTransformBatch({
    snapshot: snapshotQuery.data,
    executeCommands,
    setPositionDrafts,
  })
  const { moveNodes, commitTransforms, reset: resetTransformBatch } = transformBatch

  const availableNodeNames = useCallback(() => {
    const snapshot = queueRef.current?.currentSnapshot() ?? snapshotQuery.data
    return [
      ...(snapshot?.nodes.map((node) => node.name) ?? []),
      ...reservedNodeAliasesRef.current,
    ]
  }, [snapshotQuery.data])

  const suggestNodeAlias = useCallback((preferred: string) => (
    uniqueNodeAlias(preferred, availableNodeNames())
  ), [availableNodeNames])

  const reserveNodeAlias = useCallback((preferred: string) => {
    const alias = suggestNodeAlias(preferred)
    reservedNodeAliasesRef.current.add(alias)
    return alias
  }, [suggestNodeAlias])

  const releaseNodeAlias = useCallback((alias: string) => {
    const normalized = normalizeNodeAlias(alias)
    for (const reserved of reservedNodeAliasesRef.current) {
      if (normalizeNodeAlias(reserved) === normalized) {
        reservedNodeAliasesRef.current.delete(reserved)
        return
      }
    }
  }, [])

  const nextTransform = useCallback((
    size: Pick<CanvasTransformDTO, 'width' | 'height'> = NODE_SIZE,
  ): CanvasTransformDTO => {
    const count = (queueRef.current?.currentSnapshot() ?? snapshotQuery.data)?.nodes.length ?? 0
    return {
      ...NODE_SIZE,
      ...size,
      x: 100 + (count % 4) * 360,
      y: 100 + Math.floor(count / 4) * 380,
    }
  }, [snapshotQuery.data])

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

  const uploadPipeline = useCanvasUploadPipeline({
    canvasId: state.canvasId,
    executeCommands,
    reserveNodeAlias,
    releaseNodeAlias,
    nextTransform,
    onUploadProgress: setUploadProgress,
    setToast,
  })
  const { uploadFiles, resetUploads } = uploadPipeline

  const openEditor = useCallback((canvasId: UUIDString) => {
    resetTransformBatch()
    resetUploads()
    resetFunctionConfigDrafts()
    reservedNodeAliasesRef.current.clear()
    queueRef.current = null
    setInitialFitPending(!hasStoredCanvasViewport(canvasId))
    setState((current) => ({
      ...current,
      view: 'editor',
      canvasId,
      selectedIds: [],
      selectedLinks: [],
      positionDrafts: {},
      viewport: loadCanvasViewport(canvasId),
      conflictMessage: null,
      textEditor: null,
    }))
  }, [resetFunctionConfigDrafts, resetTransformBatch, resetUploads])

  const openLibrary = useCallback(() => {
    resetTransformBatch()
    resetUploads()
    reservedNodeAliasesRef.current.clear()
    setInitialFitPending(false)
    setState((current) => ({
      ...current,
      view: 'library',
      canvasId: null,
      selectedIds: [],
      selectedLinks: [],
      positionDrafts: {},
      addMenuOpen: false,
      threadOpen: false,
      textEditor: null,
    }))
  }, [resetTransformBatch, resetUploads])

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
      if (
        !current.addMenuOpen
        && sameSelection(current.selectedIds, selectedIds, current.selectedLinks, selectedLinks)
      ) {
        return current
      }
      return {
        ...current,
        selectedIds,
        selectedLinks,
        addMenuOpen: false,
      }
    })
  }, [])

  const renameNode = useCallback((nodeId: UUIDString, name: string) => {
    const normalized = name.trim()
    if (!normalized) {
      return
    }
    void executeCommands([{ type: 'RENAME_NODE', nodeId, name: normalized }]).catch(() => undefined)
  }, [executeCommands])

  const editTextNode = useCallback((node: ResourceNode) => {
    const resource = node.resources[0]
    if (resource?.kind !== 'TEXT') {
      return
    }
    setState((current) => ({
      ...current,
      textEditor: {
        mode: 'edit',
        nodeId: node.id,
        name: node.name,
        markdown: resource.textContent ?? '',
      },
    }))
  }, [])

  const createTextNode = useCallback(() => {
    setState((current) => ({
      ...current,
      textEditor: {
        mode: 'create',
        nodeId: null,
        name: suggestNodeAlias('文本'),
        markdown: '# 新文本\n\n在这里编写 Markdown。',
      },
    }))
  }, [suggestNodeAlias])

  const setTextEditorDraft = useCallback((patch: { name?: string; markdown?: string }) => {
    setState((current) => current.textEditor ? {
      ...current,
      textEditor: { ...current.textEditor, ...patch },
    } : current)
  }, [])

  const closeTextEditor = useCallback(() => {
    setState((current) => ({ ...current, textEditor: null }))
  }, [])

  const saveTextEditor = useCallback(() => {
    const editor = state.textEditor
    if (!editor || !editor.name.trim() || !editor.markdown.trim()) {
      return
    }
    if (editor.mode === 'edit') {
      const commands: CanvasCommandDTO[] = [{ type: 'UPDATE_TEXT_NODE', nodeId: editor.nodeId, markdown: editor.markdown }]
      const current = queueRef.current?.currentSnapshot() ?? snapshotQuery.data
      const node = current?.nodes.find((item) => item.id === editor.nodeId)
      if (node && node.name !== editor.name.trim()) {
        commands.push({ type: 'RENAME_NODE', nodeId: editor.nodeId, name: editor.name.trim() })
      }
      void executeCommands(commands).then(() => {
        setState((current) => ({ ...current, textEditor: null }))
      }).catch(() => undefined)
      return
    }
    const alias = reserveNodeAlias(editor.name)
    void executeCommands([{
      type: 'CREATE_TEXT_NODE',
      nodeId: crypto.randomUUID(),
      name: alias,
      markdown: editor.markdown,
      transform: nextTransform(),
    }]).then(() => {
      setState((current) => ({ ...current, textEditor: null }))
    }).catch(() => undefined).finally(() => releaseNodeAlias(alias))
  }, [executeCommands, nextTransform, releaseNodeAlias, reserveNodeAlias, snapshotQuery.data, state.textEditor])

  const createFunctionNode = useCallback((outputKind: 'IMAGE' | 'VIDEO') => {
    const model = modelsQuery.data?.find((item) => item.outputKind === outputKind && item.available)
      ?? modelsQuery.data?.find((item) => item.outputKind === outputKind)
    if (!model || !model.available) {
      setToast(model?.unavailableReason || `没有可用的${outputKind === 'IMAGE' ? '图片' : '视频'}模型`)
      return
    }
    const config = createDefaultFunctionConfig(model)
    config.prompt.segments = [{ type: 'TEXT', text: outputKind === 'IMAGE' ? '描述要生成的图片' : '描述要生成的视频' }]
    const alias = reserveNodeAlias(outputKind === 'IMAGE' ? '图片生成' : '视频生成')
    void executeCommands([{
      type: 'CREATE_FUNCTION_NODE',
      nodeId: crypto.randomUUID(),
      name: alias,
      modelKey: model.key,
      configJson: JSON.stringify(config),
      transform: nextTransform(),
    }]).catch(() => undefined).finally(() => releaseNodeAlias(alias))
  }, [
    executeCommands,
    modelsQuery.data,
    nextTransform,
    releaseNodeAlias,
    reserveNodeAlias,
    setToast,
  ])

  const createGroup = useCallback(() => {
    const snapshot = snapshotQuery.data ? projectCanvasSnapshot(snapshotQuery.data) : null
    const members = state.selectedIds
      .map((id) => snapshot?.resourceNodes.find((node) => node.id === id))
      .filter((node): node is ResourceNode => Boolean(node))
    if (
      members.length === 0
      || members.length !== state.selectedIds.length
      || members.some((node) => node.groupId)
    ) {
      setToast('请先选择未分组的资源节点。')
      return
    }
    const memberTransforms = members.map((node) => {
      const draft = state.positionDrafts[node.id]
      return draft ? { ...node.transform, ...draft } : node.transform
    })
    const minX = Math.min(...memberTransforms.map((transform) => transform.x)) - 32
    const minY = Math.min(...memberTransforms.map((transform) => transform.y)) - 52
    const maxX = Math.max(...memberTransforms.map((transform) => transform.x + transform.width)) + 32
    const maxY = Math.max(...memberTransforms.map((transform) => transform.y + transform.height)) + 32
    void executeCommands([{
      type: 'CREATE_GROUP',
      groupId: crypto.randomUUID(),
      title: '分组',
      transform: { x: minX, y: minY, width: maxX - minX, height: maxY - minY },
      memberNodeIds: members.map((node) => node.id),
    }]).catch(() => undefined)
  }, [
    executeCommands,
    setToast,
    snapshotQuery.data,
    state.positionDrafts,
    state.selectedIds,
  ])

  /** 解组单个 Group：UNGROUP 语义本身会解除成员并删除边界，不追加 DELETE_GROUP。 */
  const ungroupGroup = useCallback((groupId: UUIDString) => {
    const memberNodeIds = (snapshotQuery.data?.nodes ?? [])
      .filter((node) => node.groupId === groupId)
      .map((node) => node.id)
    if (memberNodeIds.length === 0) {
      setToast('当前分组没有成员。')
      return
    }
    void executeCommands([{ type: 'UNGROUP', groupId, memberNodeIds }]).catch(() => undefined)
  }, [executeCommands, setToast, snapshotQuery.data?.nodes])

  const renameGroup = useCallback((groupId: UUIDString, title: string) => {
    const normalized = title.trim()
    if (!normalized) {
      return
    }
    void executeCommands([{ type: 'RENAME_GROUP', groupId, title: normalized }]).catch(() => undefined)
  }, [executeCommands])

  const deleteGroup = useCallback((groupId: UUIDString) => {
    void executeCommands([{ type: 'DELETE_GROUP', groupId }]).catch(() => undefined)
  }, [executeCommands])

  const handleAddAction = useCallback((action: AddMenuAction) => {
    setState((current) => ({ ...current, addMenuOpen: false }))
    if (action === 'text-resource') {
      createTextNode()
    } else if (action === 'image-function') {
      createFunctionNode('IMAGE')
    } else if (action === 'video-function') {
      createFunctionNode('VIDEO')
    }
  }, [createFunctionNode, createTextNode])

  const deleteSelection = useCallback(() => {
    const commands: CanvasCommandDTO[] = []
    for (const link of state.selectedLinks) {
      commands.push({
        type: 'DELETE_LINK',
        sourceNodeId: link.sourceNodeId,
        targetNodeId: link.targetNodeId,
      })
    }
    if (commands.length === 0) {
      return
    }
    void executeCommands(commands).then(() => {
      setState((current) => ({
        ...current,
        selectedIds: [],
        selectedLinks: [],
      }))
    }).catch(() => undefined)
  }, [
    executeCommands,
    state.selectedLinks,
  ])

  const createLink = useCallback((sourceNodeId: UUIDString, targetNodeId: UUIDString) => {
    void executeCommands([{ type: 'CREATE_LINK', sourceNodeId, targetNodeId }]).catch(() => undefined)
  }, [executeCommands])

  const deleteLink = useCallback((sourceNodeId: UUIDString, targetNodeId: UUIDString) => {
    void executeCommands([{ type: 'DELETE_LINK', sourceNodeId, targetNodeId }]).catch(() => undefined)
  }, [executeCommands])

  const deleteNode = useCallback((nodeId: UUIDString) => {
    void flushFunctionConfig(nodeId)
      .catch(() => undefined)
      .then(() => executeCommands([{ type: 'DELETE_NODE', nodeId }]))
      .then(() => {
        setState((current) => ({
          ...current,
          selectedIds: current.selectedIds.filter((id) => id !== nodeId),
          selectedLinks: current.selectedLinks.filter((link) => (
            link.sourceNodeId !== nodeId && link.targetNodeId !== nodeId
          )),
        }))
      })
      .catch(() => undefined)
  }, [executeCommands, flushFunctionConfig])

  const nodeCallbacks: CanvasNodeCallbacks = useMemo(() => ({
    editTextNode,
  }), [editTextNode])

  const toggleAddMenu = useCallback(() => {
    setState((current) => ({
      ...current,
      addMenuOpen: !current.addMenuOpen,
      threadOpen: false,
    }))
  }, [])
  const closeAddMenu = useCallback(() => {
    setState((current) => ({ ...current, addMenuOpen: false, addMenuIndex: 0 }))
  }, [])
  const setAddMenuIndex = useCallback((addMenuIndex: number) => {
    setState((current) => ({ ...current, addMenuIndex }))
  }, [])
  const openThread = useCallback(() => {
    setState((current) => ({
      ...current,
      threadOpen: true,
      addMenuOpen: false,
    }))
  }, [])
  const collapseThread = useCallback(() => {
    setState((current) => ({ ...current, threadOpen: false }))
  }, [])
  // 快捷键只负责展开面板；composer 聚焦由共享 ThreadComposer 自己管理。
  const focusThread = useCallback(() => {
    openThread()
  }, [openThread])

  useCanvasKeyboard({
    view: state.view,
    stageElementRef,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    clearSelection: () => setSelection([]),
    deleteSelection,
    focusThread,
    createTextNode,
    closeOverlays: () => {
      closeAddMenu()
      closeTextEditor()
      closeContextMenuRef.current?.()
    },
  })

  return {
    state,
    snapshot: snapshotQuery.data ?? null,
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
    models: modelsQuery.data ?? [],
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
    deleteSelection,
    createTextNode,
    setTextEditorDraft,
    closeTextEditor,
    saveTextEditor,
    createFunctionNode,
    scheduleFunctionConfig,
    flushFunctionConfig,
    startFunctionRun,
    cancelFunctionRun,
    createGroup,
    ungroupGroup,
    renameGroup,
    deleteGroup,
    renameNode,
    editTextNode,
    deleteNode,
    uploadFiles,
    handleAddAction,
    toggleAddMenu,
    closeAddMenu,
    setAddMenuIndex,
    openThread,
    collapseThread,
    focusThread,
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
