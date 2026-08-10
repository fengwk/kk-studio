import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CanvasCommandConflictError, CanvasCommandQueue } from '@/features/canvas/command-queue'
import type { ResourceNode } from '@/features/canvas/domain'
import { createDefaultFunctionConfig } from '@/features/canvas/generation'
import { groupIdFromFlowId } from '@/features/canvas/projection'
import type {
  AddMenuAction,
  AgentContextMode,
  CanvasLocalState,
  CanvasNodeCallbacks,
  CanvasPositionUpdate,
  CanvasTool,
  PendingFunctionConfig,
  StageMetrics,
} from '@/features/canvas/types'
import {
  DEFAULT_CANVAS_VIEWPORT,
  loadCanvasViewport,
  saveCanvasViewport,
  type StoredCanvasViewport,
} from '@/features/canvas/viewport-storage'
import { useCanvasKeyboard } from '@/features/canvas/useCanvasKeyboard'
import type {
  CanvasCommandDTO,
  CanvasFunctionConfigDTO,
  CanvasFunctionRunDTO,
  CanvasResourceKind,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
  DecimalString,
} from '@/shared/api/contracts/studio'
import {
  cancelCanvasFunctionRun,
  completeCanvasUpload,
  getCanvas,
  getCanvasFunctionRun,
  listCanvasFunctionModels,
  reserveCanvasUpload,
  startCanvasFunctionRun,
  uploadCanvasFile,
} from '@/shared/api/studio-service'
import { queryKeys } from '@/shared/lib/query-keys'

const DEFAULT_STAGE: StageMetrics = { width: 960, height: 640, dockTop: 520 }
const NODE_SIZE: CanvasTransformDTO = { x: 120, y: 120, width: 320, height: 260 }

export function useCanvasController() {
  const queryClient = useQueryClient()
  const [state, setState] = useState<CanvasLocalState>({
    view: 'library',
    canvasId: null,
    selectedIds: [],
    selectedLinks: [],
    positionDrafts: {},
    viewport: { ...DEFAULT_CANVAS_VIEWPORT },
    tool: 'select',
    toast: null,
    addMenuOpen: false,
    addMenuIndex: 0,
    threadOpen: false,
    agentPrompt: '',
    contextMode: 'selection',
    messages: [],
    uploadProgress: {},
    commandPending: false,
    conflictMessage: null,
    textEditor: null,
  })
  const [stageMetrics, setStageMetrics] = useState(DEFAULT_STAGE)
  const fitViewRef = useRef<(() => void) | null>(null)
  const focusSelectionRef = useRef<(() => void) | null>(null)
  const zoomRef = useRef<((scale: number) => void) | null>(null)
  const stageElementRef = useRef<HTMLElement | null>(null)
  const agentPromptRef = useRef<HTMLTextAreaElement | null>(null)
  const dockAddRef = useRef<HTMLButtonElement | null>(null)
  const queueRef = useRef<CanvasCommandQueue | null>(null)
  const pendingCommandCountRef = useRef(0)
  const transformTimerRef = useRef<number | null>(null)
  const pendingNodeTransformsRef = useRef(new Map<DecimalString, CanvasTransformDTO>())
  const pendingGroupMovesRef = useRef(new Map<DecimalString, { x: number; y: number }>())
  const pendingFunctionConfigsRef = useRef(new Map<DecimalString, PendingFunctionConfig>())
  const functionConfigTimersRef = useRef(new Map<DecimalString, number>())

  const snapshotQuery = useQuery({
    queryKey: state.canvasId ? queryKeys.studio.canvas(state.canvasId) : ['studio', 'canvas', 'none'],
    queryFn: ({ signal }) => getCanvas(state.canvasId as DecimalString, { signal }),
    enabled: state.view === 'editor' && Boolean(state.canvasId),
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.studio.canvasModels,
    queryFn: ({ signal }) => listCanvasFunctionModels({ signal }),
    enabled: state.view === 'editor',
  })
  const runningNodeIds = useMemo(
    () => snapshotQuery.data?.nodes
      .filter((node) => node.run?.status === 'RUNNING')
      .map((node) => node.id) ?? [],
    [snapshotQuery.data?.nodes],
  )
  const runQueries = useQueries({
    queries: runningNodeIds.map((nodeId) => ({
      queryKey: state.canvasId
        ? queryKeys.studio.canvasRun(state.canvasId, nodeId)
        : ['studio', 'canvas-run', 'none', nodeId],
      queryFn: ({ signal }: { signal: AbortSignal }) => (
        getCanvasFunctionRun(state.canvasId as DecimalString, nodeId, { signal })
      ),
      enabled: state.view === 'editor' && Boolean(state.canvasId),
      refetchInterval: 800,
      retry: false,
    })),
  })
  const polledRuns = runQueries
    .map((query) => query.data)
    .filter((run): run is CanvasFunctionRunDTO => Boolean(run))
  const polledRunSignature = polledRuns
    .map((run) => (
      `${run.nodeId}:${run.requestId}:${run.status}:${run.stage}:${run.error ?? ''}:${run.updatedAt}`
    ))
    .join('|')

  useEffect(() => {
    if (!state.canvasId || polledRuns.length === 0) {
      return
    }
    const canvasId = state.canvasId
    let succeeded = false
    queryClient.setQueryData<CanvasSnapshotDTO>(
      queryKeys.studio.canvas(canvasId),
      (current) => {
        let next = current
        for (const run of polledRuns) {
          next = patchSnapshotRun(next, run)
          succeeded ||= run.status === 'SUCCEEDED'
        }
        return next
      },
    )
    if (succeeded) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvas(canvasId) })
    }
    // The signature tracks endpoint state without making the query result array an effect dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [polledRunSignature, queryClient, state.canvasId])

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

  useEffect(() => {
    if (!state.toast) {
      return
    }
    const timer = window.setTimeout(() => {
      setState((current) => ({ ...current, toast: null }))
    }, 2600)
    return () => window.clearTimeout(timer)
  }, [state.toast])

  useEffect(() => () => {
    if (transformTimerRef.current !== null) {
      window.clearTimeout(transformTimerRef.current)
    }
    for (const timer of functionConfigTimersRef.current.values()) {
      window.clearTimeout(timer)
    }
    functionConfigTimersRef.current.clear()
  }, [])

  const setToast = useCallback((toast: string) => {
    setState((current) => ({ ...current, toast }))
  }, [])

  const openEditor = useCallback((canvasId: DecimalString) => {
    if (transformTimerRef.current !== null) {
      window.clearTimeout(transformTimerRef.current)
      transformTimerRef.current = null
    }
    pendingNodeTransformsRef.current.clear()
    pendingGroupMovesRef.current.clear()
    for (const timer of functionConfigTimersRef.current.values()) {
      window.clearTimeout(timer)
    }
    functionConfigTimersRef.current.clear()
    pendingFunctionConfigsRef.current.clear()
    queueRef.current = null
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
  }, [])

  const openLibrary = useCallback(() => {
    if (transformTimerRef.current !== null) {
      window.clearTimeout(transformTimerRef.current)
      transformTimerRef.current = null
    }
    pendingNodeTransformsRef.current.clear()
    pendingGroupMovesRef.current.clear()
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

  const flushTransforms = useCallback(() => {
    transformTimerRef.current = null
    const commands: CanvasCommandDTO[] = []
    const submittedDrafts: Record<string, { x: number; y: number }> = {}
    const updates = [...pendingNodeTransformsRef.current].map(([nodeId, transform]) => ({
      nodeId,
      transform,
    }))
    for (const [nodeId, transform] of pendingNodeTransformsRef.current) {
      submittedDrafts[nodeId] = { x: transform.x, y: transform.y }
    }
    pendingNodeTransformsRef.current.clear()
    for (const [groupId, position] of pendingGroupMovesRef.current) {
      commands.push({ type: 'MOVE_GROUP', groupId, ...position })
      submittedDrafts[`group:${groupId}`] = position
    }
    pendingGroupMovesRef.current.clear()
    if (updates.length > 0) {
      commands.push({ type: 'UPDATE_NODE_TRANSFORMS', updates })
    }
    if (commands.length > 0) {
      void executeCommands(commands)
        .catch(() => undefined)
        .finally(() => {
          setState((current) => ({
            ...current,
            positionDrafts: removeSubmittedDrafts(current.positionDrafts, submittedDrafts),
          }))
        })
    }
  }, [executeCommands])

  const scheduleTransformFlush = useCallback(() => {
    if (transformTimerRef.current !== null) {
      window.clearTimeout(transformTimerRef.current)
    }
    transformTimerRef.current = window.setTimeout(flushTransforms, 180)
  }, [flushTransforms])

  const moveNodes = useCallback((updates: CanvasPositionUpdate[]) => {
    const snapshot = snapshotQuery.data
    if (!snapshot) {
      return
    }
    setState((current) => {
      const positionDrafts = { ...current.positionDrafts }
      for (const update of updates) {
        if (update.kind === 'group') {
          const groupId = groupIdFromFlowId(update.id)
          if (!groupId) {
            continue
          }
          const group = snapshot.groups.find((item) => item.id === groupId)
          if (!group) {
            continue
          }
          const position = {
            x: update.transform.x,
            y: update.transform.y,
          }
          positionDrafts[update.id] = position
          pendingGroupMovesRef.current.set(groupId, position)
        } else {
          positionDrafts[update.id] = {
            x: update.transform.x,
            y: update.transform.y,
          }
          pendingNodeTransformsRef.current.set(update.id as DecimalString, update.transform)
        }
      }
      return { ...current, positionDrafts }
    })
  }, [snapshotQuery.data])

  const commitTransforms = useCallback(() => {
    scheduleTransformFlush()
  }, [scheduleTransformFlush])

  const setViewport = useCallback((viewport: StoredCanvasViewport) => {
    setState((current) => {
      if (current.canvasId) {
        saveCanvasViewport(current.canvasId, viewport)
      }
      return { ...current, viewport }
    })
  }, [])

  const setSelection = useCallback((
    selectedIds: string[],
    selectedLinks: CanvasLocalState['selectedLinks'] = [],
  ) => {
    setState((current) => ({
      ...current,
      selectedIds,
      selectedLinks,
      addMenuOpen: false,
    }))
  }, [])

  const renameNode = useCallback((nodeId: DecimalString, name: string) => {
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
        markdown: resource.text ?? '',
      },
    }))
  }, [])

  const nodeCallbacks: CanvasNodeCallbacks = useMemo(() => ({
    renameNode,
    editTextNode,
  }), [editTextNode, renameNode])

  const nextTransform = useCallback((): CanvasTransformDTO => {
    const count = snapshotQuery.data?.nodes.length ?? 0
    return {
      ...NODE_SIZE,
      x: 100 + (count % 4) * 360,
      y: 100 + Math.floor(count / 4) * 300,
    }
  }, [snapshotQuery.data?.nodes.length])

  const createTextNode = useCallback(() => {
    setState((current) => ({
      ...current,
      textEditor: {
        mode: 'create',
        nodeId: null,
        name: '文本',
        markdown: '# 新文本\n\n在这里编写 Markdown。',
      },
    }))
  }, [])

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
    const command: CanvasCommandDTO = editor.mode === 'create'
      ? {
        type: 'CREATE_TEXT_NODE',
        name: editor.name.trim(),
        markdown: editor.markdown,
        transform: nextTransform(),
      }
      : {
        type: 'UPDATE_TEXT_NODE',
        nodeId: editor.nodeId,
        markdown: editor.markdown,
      }
    void executeCommands([command]).then(() => {
      setState((current) => ({ ...current, textEditor: null }))
    }).catch(() => undefined)
  }, [executeCommands, nextTransform, state.textEditor])

  const createFunctionNode = useCallback((outputKind: 'IMAGE' | 'VIDEO') => {
    const model = modelsQuery.data?.find((item) => item.outputKind === outputKind && item.available)
      ?? modelsQuery.data?.find((item) => item.outputKind === outputKind)
    if (!model || !model.available) {
      setToast(model?.unavailableReason || `没有可用的${outputKind === 'IMAGE' ? '图片' : '视频'}模型`)
      return
    }
    const config = createDefaultFunctionConfig(model)
    config.prompt.segments = [{ type: 'TEXT', text: outputKind === 'IMAGE' ? '描述要生成的图片' : '描述要生成的视频' }]
    void executeCommands([{
      type: 'CREATE_FUNCTION_NODE',
      name: outputKind === 'IMAGE' ? '图片生成' : '视频生成',
      modelKey: model.key,
      configJson: JSON.stringify(config),
      transform: nextTransform(),
    }]).catch(() => undefined)
  }, [executeCommands, modelsQuery.data, nextTransform, setToast])

  const createGroup = useCallback(() => {
    const snapshot = snapshotQuery.data
    const members = snapshot?.nodes.filter((node) => state.selectedIds.includes(node.id) && !node.groupId) ?? []
    if (members.length === 0) {
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

  const ungroupSelection = useCallback(() => {
    const snapshot = snapshotQuery.data
    if (!snapshot) {
      return
    }
    const byGroup = new Map<DecimalString, DecimalString[]>()
    for (const node of snapshot.nodes) {
      if (!node.groupId || !state.selectedIds.includes(node.id)) {
        continue
      }
      const members = byGroup.get(node.groupId) ?? []
      members.push(node.id)
      byGroup.set(node.groupId, members)
    }
    const commands: CanvasCommandDTO[] = [...byGroup].map(([groupId, memberNodeIds]) => ({
      type: 'UNGROUP',
      groupId,
      memberNodeIds,
    }))
    for (const id of state.selectedIds) {
      const groupId = groupIdFromFlowId(id)
      if (groupId && !commands.some((command) => (
        (command.type === 'UNGROUP' || command.type === 'DELETE_GROUP')
        && command.groupId === groupId
      ))) {
        commands.push({ type: 'DELETE_GROUP', groupId })
      }
    }
    if (commands.length === 0) {
      setToast('当前选区没有已分组的成员。')
      return
    }
    void executeCommands(commands).catch(() => undefined)
  }, [executeCommands, setToast, snapshotQuery.data, state.selectedIds])

  const uploadFiles = useCallback(async (files: FileList | File[]) => {
    if (!state.canvasId) {
      return
    }
    const canvasId = state.canvasId
    for (const file of Array.from(files)) {
      const kind = fileKind(file)
      const localId = `${file.name}:${file.lastModified}:${file.size}`
      if (!kind) {
        setToast(`不支持的文件类型：${file.name}`)
        continue
      }
      try {
        setState((current) => ({
          ...current,
          uploadProgress: { ...current.uploadProgress, [localId]: 0.1 },
        }))
        const reservation = await reserveCanvasUpload(canvasId, {
          kind,
          filename: file.name,
          mediaType: file.type || fallbackMediaType(kind),
          size: String(file.size) as DecimalString,
        })
        setState((current) => ({
          ...current,
          uploadProgress: { ...current.uploadProgress, [localId]: 0.35 },
        }))
        await uploadCanvasFile(reservation, file)
        setState((current) => ({
          ...current,
          uploadProgress: { ...current.uploadProgress, [localId]: 0.8 },
        }))
        const resource = await completeCanvasUpload(canvasId, reservation.uploadId)
        await executeCommands([{
          type: 'CREATE_RESOURCE_NODE',
          name: file.name,
          resourceIds: [resource.id],
          transform: nextTransform(),
        }])
        setState((current) => {
          const progress = { ...current.uploadProgress }
          delete progress[localId]
          return { ...current, uploadProgress: progress, toast: `已上传 ${file.name}` }
        })
      } catch (error) {
        setState((current) => {
          const progress = { ...current.uploadProgress }
          delete progress[localId]
          return {
            ...current,
            uploadProgress: progress,
            toast: error instanceof Error ? error.message : `上传 ${file.name} 失败`,
          }
        })
      }
    }
  }, [executeCommands, nextTransform, setToast, state.canvasId])

  const handleAddAction = useCallback((action: AddMenuAction) => {
    setState((current) => ({ ...current, addMenuOpen: false }))
    if (action === 'text-resource') {
      createTextNode()
    } else if (action === 'image-function') {
      createFunctionNode('IMAGE')
    } else if (action === 'video-function') {
      createFunctionNode('VIDEO')
    } else if (action === 'group') {
      createGroup()
    }
  }, [createFunctionNode, createGroup, createTextNode])

  const deleteSelection = useCallback(() => {
    const commands: CanvasCommandDTO[] = []
    const nodeIds = new Set(snapshotQuery.data?.nodes.map((node) => node.id) ?? [])
    for (const id of state.selectedIds) {
      const groupId = groupIdFromFlowId(id)
      if (groupId) {
        commands.push({ type: 'DELETE_GROUP', groupId })
      } else if (nodeIds.has(id as DecimalString)) {
        commands.push({ type: 'DELETE_NODE', nodeId: id as DecimalString })
      }
    }
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
    snapshotQuery.data?.nodes,
    state.selectedIds,
    state.selectedLinks,
  ])

  const createLink = useCallback((sourceNodeId: DecimalString, targetNodeId: DecimalString) => {
    void executeCommands([{ type: 'CREATE_LINK', sourceNodeId, targetNodeId }]).catch(() => undefined)
  }, [executeCommands])

  const deleteLink = useCallback((sourceNodeId: DecimalString, targetNodeId: DecimalString) => {
    void executeCommands([{ type: 'DELETE_LINK', sourceNodeId, targetNodeId }]).catch(() => undefined)
  }, [executeCommands])

  const flushFunctionConfig = useCallback(async (nodeId: DecimalString) => {
    const timer = functionConfigTimersRef.current.get(nodeId)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      functionConfigTimersRef.current.delete(nodeId)
    }
    const pending = pendingFunctionConfigsRef.current.get(nodeId)
    if (!pending) {
      return
    }
    pendingFunctionConfigsRef.current.delete(nodeId)
    await executeCommands([{
      type: 'UPDATE_FUNCTION',
      nodeId: pending.nodeId,
      modelKey: pending.modelKey,
      configJson: JSON.stringify(pending.config),
    }])
  }, [executeCommands])

  const scheduleFunctionConfig = useCallback((
    nodeId: DecimalString,
    modelKey: string,
    config: CanvasFunctionConfigDTO,
  ) => {
    pendingFunctionConfigsRef.current.set(nodeId, { nodeId, modelKey, config })
    const existing = functionConfigTimersRef.current.get(nodeId)
    if (existing !== undefined) {
      window.clearTimeout(existing)
    }
    const timer = window.setTimeout(() => {
      functionConfigTimersRef.current.delete(nodeId)
      void flushFunctionConfig(nodeId).catch(() => undefined)
    }, 320)
    functionConfigTimersRef.current.set(nodeId, timer)
  }, [flushFunctionConfig])

  const publishRun = useCallback((run: CanvasFunctionRunDTO) => {
    if (!state.canvasId) {
      return
    }
    const canvasId = state.canvasId
    queryClient.setQueryData<CanvasFunctionRunDTO>(
      queryKeys.studio.canvasRun(canvasId, run.nodeId),
      run,
    )
    queryClient.setQueryData<CanvasSnapshotDTO>(
      queryKeys.studio.canvas(canvasId),
      (current) => patchSnapshotRun(current, run),
    )
    if (run.status === 'SUCCEEDED') {
      void queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvas(canvasId) })
    }
  }, [queryClient, state.canvasId])

  const startFunctionRun = useCallback(async (nodeId: DecimalString) => {
    if (!state.canvasId) {
      return
    }
    try {
      await flushFunctionConfig(nodeId)
      const run = await startCanvasFunctionRun(state.canvasId, nodeId, {
        requestId: crypto.randomUUID(),
      })
      publishRun(run)
    } catch (error) {
      setToast(error instanceof Error ? error.message : '启动生成失败')
    }
  }, [flushFunctionConfig, publishRun, setToast, state.canvasId])

  const cancelFunctionRun = useCallback(async (
    nodeId: DecimalString,
    requestId: string,
  ) => {
    if (!state.canvasId || !requestId) {
      return
    }
    try {
      const run = await cancelCanvasFunctionRun(state.canvasId, nodeId, { requestId })
      publishRun(run)
    } catch (error) {
      setToast(error instanceof Error ? error.message : '取消生成失败')
    }
  }, [publishRun, setToast, state.canvasId])

  const setAgentPrompt = useCallback((agentPrompt: string) => {
    setState((current) => ({ ...current, agentPrompt }))
  }, [])

  const sendAgent = useCallback(() => {
    setState((current) => {
      const text = current.agentPrompt.trim()
      if (!text) {
        return { ...current, toast: '请输入要交给 Agent 的任务。' }
      }
      return {
        ...current,
        agentPrompt: '',
        threadOpen: true,
        messages: [
          ...current.messages,
          { kind: 'user', text },
          {
            kind: 'agent',
            text: '我已读取当前真实 ResourceNode 选区。Canvas v1 暂不把文本生成 Function 接入后端；现有 Agent 最后回答仍会保留在此处。',
          },
        ],
      }
    })
  }, [])

  const contextNodes = useMemo(() => {
    const nodes = snapshotQuery.data?.nodes ?? []
    return state.contextMode === 'whole'
      ? nodes
      : nodes.filter((node) => state.selectedIds.includes(node.id))
  }, [snapshotQuery.data?.nodes, state.contextMode, state.selectedIds])
  const contextDescription = contextNodes.length === 0
    ? '尚未选择 ResourceNode。'
    : contextNodes.map((node) => {
        const kinds = [...new Set(node.resources.map((resource) => resource.kind))].join('/')
        return `${node.name}（${kinds || node.function?.modelKey || 'Function'}，${node.resources.length} 个资源）`
      }).join('；')

  const setContextMode = useCallback((contextMode: AgentContextMode) => {
    setState((current) => ({ ...current, contextMode }))
  }, [])
  const setTool = useCallback((tool: CanvasTool) => {
    setState((current) => ({ ...current, tool }))
  }, [])
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
  const focusAgentDock = useCallback(() => {
    openThread()
    window.requestAnimationFrame(() => agentPromptRef.current?.focus())
  }, [openThread])

  useCanvasKeyboard({
    view: state.view,
    stageElementRef,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    clearSelection: () => setSelection([]),
    deleteSelection,
    focusAgentPrompt: focusAgentDock,
    setTool,
    createTextNode,
    closeOverlays: () => {
      closeAddMenu()
      collapseThread()
      closeTextEditor()
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
    agentPromptRef,
    dockAddRef,
    snapshotQuery,
    modelsQuery,
    models: modelsQuery.data ?? [],
    nodeCallbacks,
    openEditor,
    openLibrary,
    setToast,
    setViewport,
    setSelection,
    moveNodes,
    commitTransforms,
    setTool,
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
    ungroupSelection,
    uploadFiles,
    handleAddAction,
    toggleAddMenu,
    closeAddMenu,
    setAddMenuIndex,
    setAgentPrompt,
    sendAgent,
    openThread,
    collapseThread,
    focusAgentDock,
    setContextMode,
    contextCount: contextNodes.length,
    contextDescription,
  }
}

function fileKind(file: File): Exclude<CanvasResourceKind, 'TEXT'> | null {
  if (file.type.startsWith('image/')) {
    return 'IMAGE'
  }
  if (file.type.startsWith('video/')) {
    return 'VIDEO'
  }
  if (file.type.startsWith('audio/')) {
    return 'AUDIO'
  }
  const extension = file.name.split('.').at(-1)?.toLowerCase()
  if (extension && ['jpg', 'jpeg', 'png', 'webp', 'heic', 'heif'].includes(extension)) {
    return 'IMAGE'
  }
  if (extension && ['mp4', 'mov'].includes(extension)) {
    return 'VIDEO'
  }
  if (extension && ['wav', 'mp3'].includes(extension)) {
    return 'AUDIO'
  }
  return null
}

function fallbackMediaType(kind: Exclude<CanvasResourceKind, 'TEXT'>): string {
  if (kind === 'IMAGE') {
    return 'image/jpeg'
  }
  if (kind === 'VIDEO') {
    return 'video/mp4'
  }
  return 'audio/mpeg'
}

function removeSubmittedDrafts(
  current: Record<string, { x: number; y: number }>,
  submitted: Record<string, { x: number; y: number }>,
): Record<string, { x: number; y: number }> {
  const next = { ...current }
  for (const [id, position] of Object.entries(submitted)) {
    const draft = next[id]
    if (draft?.x === position.x && draft.y === position.y) {
      delete next[id]
    }
  }
  return next
}

function patchSnapshotRun(
  snapshot: CanvasSnapshotDTO | undefined,
  run: CanvasFunctionRunDTO,
): CanvasSnapshotDTO | undefined {
  if (!snapshot) {
    return snapshot
  }
  const nodeIndex = snapshot.nodes.findIndex((node) => node.id === run.nodeId)
  if (nodeIndex < 0) {
    return snapshot
  }
  const currentRun = snapshot.nodes[nodeIndex]?.run
  if (
    currentRun?.requestId === run.requestId
    && currentRun.status === run.status
    && currentRun.stage === run.stage
    && currentRun.error === run.error
    && currentRun.updatedAt === run.updatedAt
  ) {
    return snapshot
  }
  const nodes = [...snapshot.nodes]
  nodes[nodeIndex] = { ...nodes[nodeIndex], run }
  return { ...snapshot, nodes }
}

export type CanvasController = ReturnType<typeof useCanvasController>
