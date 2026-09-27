import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useCanvasUploadPipeline } from '@/features/canvas/canvas-upload'
import { CanvasCommandConflictError, CanvasCommandQueue } from '@/features/canvas/command-queue'
import { useCanvasVersionEvents } from '@/features/canvas/canvas-version-events'
import {
  projectCanvasSnapshot,
  type ResourceNode,
} from '@/features/canvas/domain'
import {
  isNodeDirty,
  overlayNodeWithDraft,
  removeDraftField,
  type CanvasNodeDraft,
} from '@/features/canvas/canvas-drafts'
import {
  deleteCanvasDraft,
  loadCanvasDrafts,
  saveCanvasDraft,
} from '@/features/canvas/canvas-draft-storage'
import {
  CanvasStorageUnavailableError,
  onCanvasStorageError,
} from '@/features/canvas/canvas-local-store'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
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
  listCanvasFunctions,
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
    drafts: {},
    storageError: null,
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
  const [draftReloadToken, setDraftReloadToken] = useState(0)
  const [draftLoadEpoch, setDraftLoadEpoch] = useState(0)
  const fitViewRef = useRef<(() => void) | null>(null)
  const focusSelectionRef = useRef<(() => void) | null>(null)
  const zoomRef = useRef<((scale: number) => void) | null>(null)
  const stageElementRef = useRef<HTMLElement | null>(null)
  const dockAddRef = useRef<HTMLButtonElement | null>(null)
  const closeContextMenuRef = useRef<(() => void) | null>(null)
  const queueRef = useRef<CanvasCommandQueue | null>(null)
  const pendingCommandCountRef = useRef(0)
  const reservedNodeAliasesRef = useRef(new Set<string>())
  const applyDraftAcksRef = useRef<(acks: CanvasDraftAck[]) => void>(() => undefined)
  /** 该画布“已落盘草稿”的持久基线；canvasId 不匹配表示尚未加载完成，禁止按差集删除。 */
  const draftBaselineRef = useRef<{ canvasId: string | null; drafts: Record<string, CanvasNodeDraft> }>({
    canvasId: null,
    drafts: {},
  })

  const snapshotQuery = useQuery({
    queryKey: state.canvasId ? queryKeys.studio.canvas(state.canvasId) : ['studio', 'canvas', 'none'],
    queryFn: ({ signal }) => getCanvas(state.canvasId as UUIDString, { signal }),
    enabled: state.view === 'editor' && Boolean(state.canvasId),
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.studio.canvasModels,
    queryFn: ({ signal }) => listCanvasFunctions({ signal }),
    enabled: state.view === 'editor',
  })

  // 恢复 IndexedDB 中的持久化草稿（按 canvasId 严格隔离）
  useEffect(() => {
    if (!state.canvasId || state.view !== 'editor') {
      return
    }
    const canvasId = state.canvasId
    // 加载开始即撤销持久基线：保存 effect 在加载完成前绝不删除（或覆盖）旧的已落盘草稿。
    draftBaselineRef.current = { canvasId: null, drafts: {} }
    let cancelled = false
    void loadCanvasDrafts(canvasId).then((persisted) => {
      if (cancelled) {
        return
      }
      setState((current) => {
        if (current.canvasId !== canvasId) {
          return current
        }
        // 远端/持久草稿为底，本次会话中已经输入的内容优先，绝不因为加载而丢失用户输入。
        const mergedDrafts = { ...persisted, ...current.drafts }
        const mergedPositions = { ...current.positionDrafts }
        for (const [id, draft] of Object.entries(mergedDrafts)) {
          if (draft.position && !mergedPositions[id]) {
            mergedPositions[id] = draft.position
          }
        }
        return {
          ...current,
          drafts: mergedDrafts,
          positionDrafts: mergedPositions,
        }
      })
      // 持久基线 = 真正已落盘的集合；合并后的新增/编辑会在下一轮保存 effect 中落盘。
      draftBaselineRef.current = { canvasId, drafts: persisted }
      setDraftLoadEpoch((epoch) => epoch + 1)
    }).catch((err) => {
      console.warn('[canvas] Failed to load drafts from storage:', err)
      setState((current) => ({
        ...current,
        storageError: err instanceof Error ? err.message : String(err),
        toast: '本地草稿保存失败，数据未持久化落盘',
      }))
    })
    return () => {
      cancelled = true
    }
  }, [state.canvasId, state.view, draftReloadToken])

  // 监听持久化存储异常：IndexedDB 不可用与落盘失败都必须明确告警，绝不假装已保存。
  useEffect(() => {
    return onCanvasStorageError((error) => {
      const toast = error instanceof CanvasStorageUnavailableError
        ? error.message
        : '本地草稿保存失败，数据未持久化落盘'
      setState((current) => ({
        ...current,
        storageError: error.message,
        toast,
      }))
    })
  }, [])

  // 本地草稿持久化：草稿状态每次变化都同步到 IndexedDB（新增/更新保存，移除即删除），
  // 保证“每次操作真正进 store”，而不是散落在各分支里的旁路调用。
  // 关键约束：只有“持久基线已就绪”时才允许按差集删除，避免初始加载竞态先清掉旧草稿。
  useEffect(() => {
    const canvasId = state.canvasId
    if (!canvasId || state.view !== 'editor') {
      draftBaselineRef.current = { canvasId, drafts: {} }
      return
    }
    const baseline = draftBaselineRef.current
    if (baseline.canvasId !== canvasId) {
      // 该画布的已落盘草稿尚未加载完成：跳过，绝不用当前（可能为空）状态删除旧草稿。
      return
    }
    for (const [nodeId, draft] of Object.entries(state.drafts)) {
      if (baseline.drafts[nodeId] !== draft) {
        void saveCanvasDraft(canvasId, nodeId, draft).catch(() => undefined)
      }
    }
    for (const nodeId of Object.keys(baseline.drafts)) {
      if (!(nodeId in state.drafts)) {
        void deleteCanvasDraft(canvasId, nodeId).catch(() => undefined)
      }
    }
    draftBaselineRef.current = { canvasId, drafts: state.drafts }
  }, [state.canvasId, state.view, state.drafts, draftLoadEpoch])

  // 远端删除探测：快照中不存在但本地有未提交草稿的节点，标记为 remote_deleted 保留草稿供救援
  useEffect(() => {
    if (!snapshotQuery.data || !state.canvasId) {
      return
    }
    const snapshot = snapshotQuery.data
    const remoteNodeIds = new Set(snapshot.nodes.map((node) => node.id))
    setState((current) => {
      let changed = false
      const nextDrafts = { ...current.drafts }
      for (const [id, draft] of Object.entries(current.drafts)) {
        if (!remoteNodeIds.has(id as UUIDString) && isNodeDirty(draft) && draft.conflict?.type !== 'remote_deleted') {
          changed = true
          nextDrafts[id] = {
            ...draft,
            conflict: {
              kind: 'TARGET_MISSING',
              type: 'remote_deleted',
              message: `节点「${draft.text?.name || '已删除节点'}」已在远端被删除，本地保留未保存草稿。`,
            },
          }
        }
      }
      if (!changed) {
        return current
      }
      return {
        ...current,
        drafts: nextDrafts,
        conflictMessage: '部分节点已在远端被删除，本地保留未保存草稿。',
      }
    })
  }, [snapshotQuery.data, state.canvasId])

  useEffect(() => {
    if (!snapshotQuery.data || !state.canvasId) {
      return
    }
    if (!queueRef.current) {
      const canvasId = state.canvasId
      const queue = new CanvasCommandQueue(canvasId, {
        initialSnapshot: snapshotQuery.data,
        onSnapshot: (snapshot) => {
          queryClient.setQueryData(queryKeys.studio.canvas(canvasId as string), snapshot)
        },
      })
      queueRef.current = queue
      // 刷新 / 路由切换后，按冻结顺序精确重放原 idempotencyKey 与原始命令体。
      void queue.recover()
        .then((result) => {
          if (queueRef.current !== queue) {
            return
          }
          if (result.ackedDrafts.length > 0) {
            applyDraftAcksRef.current(result.ackedDrafts)
          }
          if (result.conflicted > 0) {
            setState((current) => ({
              ...current,
              conflictMessage: '部分未确认操作因服务端内容已变化而未能自动重放，请检查最新内容。',
            }))
          }
        })
        .catch(() => undefined)
    } else {
      const authoritative = queueRef.current.replaceSnapshot(snapshotQuery.data)
      if (authoritative !== snapshotQuery.data) {
        queryClient.setQueryData(queryKeys.studio.canvas(state.canvasId), authoritative)
      }
    }
  }, [queryClient, snapshotQuery.data, state.canvasId])

  // 应用事件 WebSocket 订阅：更高 revision 直接刷新权威 Snapshot
  const refreshCanvasSnapshot = useCallback(() => {
    const canvasId = state.canvasId
    if (!canvasId) {
      return
    }
    void queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvas(canvasId) })
  }, [queryClient, state.canvasId])

  useCanvasVersionEvents({
    canvasId: state.canvasId,
    enabled: state.view === 'editor' && Boolean(snapshotQuery.data),
    revision: snapshotQuery.data?.document.revision ?? '0',
    onSnapshot: refreshCanvasSnapshot,
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

  /**
   * 按 operation/generation 精准清除草稿：仅当草稿 generation 与冻结时一致才清除，
   * 旧 ACK 绝不清掉后来追加的本地输入。
   */
  const applyDraftAcks = useCallback((acks: CanvasDraftAck[]) => {
    if (acks.length === 0) {
      return
    }
    setState((current) => {
      const nextDrafts = { ...current.drafts }
      let changed = false
      for (const ack of acks) {
        const draft = nextDrafts[ack.nodeId]
        if (!draft) {
          continue
        }
        const field = ack.field === 'group' ? 'groupId' : ack.field
        const next = removeDraftField(draft, field, ack.generation)
        if (next) {
          nextDrafts[ack.nodeId] = next
        } else {
          delete nextDrafts[ack.nodeId]
        }
        changed = true
      }
      return changed ? { ...current, drafts: nextDrafts } : current
    })
  }, [])

  useEffect(() => {
    applyDraftAcksRef.current = applyDraftAcks
  }, [applyDraftAcks])

  const executeCommands = useCallback(async (commands: CanvasCommandDTO[], ack?: CanvasDraftAck[]) => {
    const queue = queueRef.current
    if (!queue) {
      throw new Error('Canvas snapshot is not ready')
    }
    pendingCommandCountRef.current += 1
    setState((current) => ({ ...current, commandPending: true, conflictMessage: null }))
    try {
      const snapshot = await queue.enqueue(commands, ack && ack.length > 0 ? { ack } : undefined)
      applyDraftAcks(ack ?? [])
      return snapshot
    } catch (error) {
      if (error instanceof CanvasCommandConflictError) {
        const conflicts = error.conflicts
        setState((current) => {
          const nextDrafts = { ...current.drafts }
          for (const conf of conflicts) {
            if (conf.kind === 'STALE_NODE') {
              const existing = nextDrafts[conf.nodeId] ?? { generation: 0, updatedAt: Date.now() }
              nextDrafts[conf.nodeId] = {
                ...existing,
                conflict: {
                  kind: 'STALE_NODE',
                  message: `节点在远端已被修改（${conf.group}），请对比后决定。`,
                  remoteValue: conf.current,
                  conflicts,
                },
              }
            } else if (conf.kind === 'TARGET_MISSING') {
              const existing = nextDrafts[conf.targetId] ?? { generation: 0, updatedAt: Date.now() }
              nextDrafts[conf.targetId] = {
                ...existing,
                conflict: {
                  kind: 'TARGET_MISSING',
                  type: 'remote_deleted',
                  message: '目标在远端已被删除',
                  conflicts,
                },
              }
            } else if (conf.kind === 'NODE_REFERENCED') {
              const existing = nextDrafts[conf.nodeId] ?? { generation: 0, updatedAt: Date.now() }
              nextDrafts[conf.nodeId] = {
                ...existing,
                conflict: {
                  kind: 'NODE_REFERENCED',
                  message: `节点被其他节点引用 (${conf.referencingNodeIds.join(', ')})，请先解除引用`,
                  conflicts,
                },
              }
            }
          }
          return {
            ...current,
            drafts: nextDrafts,
            conflictMessage: error.message,
            toast: '画布已在其他位置更新，请检查最新内容后重试。',
          }
        })
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
  }, [applyDraftAcks])

  const { scheduleFunctionConfig, flushFunctionConfig, resetPending: resetFunctionConfigDrafts } =
    useFunctionConfigSync(executeCommands, () => queueRef.current?.currentSnapshot() ?? snapshotQuery.data)
  const { startFunctionRun, cancelFunctionRun, resolveFunctionRun } = useCanvasFunctionRun({
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
    setState((current) => {
      const nextPositions = updater(current.positionDrafts)
      if (nextPositions === current.positionDrafts) {
        return current
      }
      const nextDrafts = { ...current.drafts }
      let changed = false
      for (const [id, pos] of Object.entries(nextPositions)) {
        const existing = nextDrafts[id]
        if (existing?.position?.x === pos.x && existing.position.y === pos.y) {
          continue
        }
        const base = existing ?? { generation: 0, updatedAt: Date.now() }
        nextDrafts[id] = {
          ...base,
          position: pos,
          updatedAt: Date.now(),
        }
        changed = true
      }
      for (const id of Object.keys(current.positionDrafts)) {
        if (id in nextPositions) {
          continue
        }
        const existing = nextDrafts[id]
        if (!existing) {
          continue
        }
        const stripped = removeDraftField(existing, 'position')
        if (stripped) {
          nextDrafts[id] = stripped
        } else {
          delete nextDrafts[id]
        }
        changed = true
      }
      if (!changed) {
        return current
      }
      return {
        ...current,
        positionDrafts: nextPositions,
        drafts: nextDrafts,
      }
    })
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
    // 重新打开画布：先撤销持久基线，等待重新加载后再允许保存/删除，避免用空状态清掉旧草稿。
    draftBaselineRef.current = { canvasId: null, drafts: {} }
    setDraftReloadToken((token) => token + 1)
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
      drafts: {},
      storageError: null,
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
      if (sameSelection(current.selectedIds, selectedIds, current.selectedLinks, selectedLinks)) {
        return current
      }
      return { ...current, selectedIds, selectedLinks }
    })
  }, [])

  const dismissDraft = useCallback((nodeId: string) => {
    setState((current) => {
      const nextDrafts = { ...current.drafts }
      delete nextDrafts[nodeId]
      const nextPositions = { ...current.positionDrafts }
      delete nextPositions[nodeId]
      return {
        ...current,
        drafts: nextDrafts,
        positionDrafts: nextPositions,
        conflictMessage: Object.values(nextDrafts).some((d) => d.conflict) ? current.conflictMessage : null,
      }
    })
  }, [])

  const saveDraftAsNewNode = useCallback(async (nodeId: string) => {
    const draft = state.drafts[nodeId]
    if (!draft) {
      return
    }
    const newId = crypto.randomUUID() as UUIDString
    const name = draft.text?.name ?? suggestNodeAlias('新节点')
    const text = draft.text?.markdown ?? ''
    const commands: CanvasCommandDTO[] = [{
      type: 'CREATE_NODE',
      nodeId: newId,
      name,
      transform: nextTransform(),
      resources: text ? [{ kind: 'TEXT', name: 'text', textContent: text }] : [],
    }]
    if (draft.function) {
      commands.push({
        type: 'SET_NODE_FUNCTION',
        nodeId: newId,
        expectedFunction: null,
        function: draft.function,
      })
    }
    try {
      await executeCommands(commands)
      dismissDraft(nodeId)
      setToast('已另存为新节点')
    } catch {
      setToast('另存为新节点失败')
    }
  }, [dismissDraft, executeCommands, nextTransform, setToast, state.drafts, suggestNodeAlias])

  const retryDraft = useCallback(async (nodeId: string) => {
    const draft = state.drafts[nodeId]
    if (!draft) {
      return
    }
    const snapshot = queueRef.current?.currentSnapshot() ?? snapshotQuery.data
    const remoteNode = snapshot?.nodes.find((n) => n.id === nodeId)
    if (!remoteNode) {
      setToast('远端节点已被删除，请选择另存为新节点')
      return
    }
    const commands: CanvasCommandDTO[] = []
    if (draft.text && draft.text.markdown !== undefined) {
      commands.push({
        type: 'SET_NODE_RESOURCES',
        nodeId: nodeId as UUIDString,
        expectedResourceIds: remoteNode.resources.map((r) => r.id),
        resources: [{ kind: 'TEXT', name: 'text', textContent: draft.text.markdown }],
      })
      if (draft.text.name && draft.text.name !== remoteNode.name) {
        commands.push({
          type: 'RENAME_NODE',
          nodeId: nodeId as UUIDString,
          expectedName: remoteNode.name,
          name: draft.text.name,
        })
      }
    }
    if (draft.function !== undefined) {
      commands.push({
        type: 'SET_NODE_FUNCTION',
        nodeId: nodeId as UUIDString,
        expectedFunction: remoteNode.function,
        function: draft.function,
      })
    }
    try {
      await executeCommands(commands)
      dismissDraft(nodeId)
      setToast('重试成功')
    } catch {
      // 冲突处理分支会自动刷新草稿状态
    }
  }, [dismissDraft, executeCommands, setToast, snapshotQuery.data, state.drafts])

  const renameNode = useCallback((nodeId: UUIDString, name: string) => {
    const normalized = name.trim()
    if (!normalized) {
      return
    }
    const current = queueRef.current?.currentSnapshot() ?? snapshotQuery.data
    const node = current?.nodes.find((item) => item.id === nodeId)
    if (!node || node.name === normalized) {
      return
    }
    void executeCommands([{
      type: 'RENAME_NODE',
      nodeId,
      expectedName: node.name,
      name: normalized,
    }]).catch(() => undefined)
  }, [executeCommands, snapshotQuery.data])

  const editTextNode = useCallback((node: ResourceNode) => {
    const resource = node.resources[0]
    if (!resource || resource.kind !== 'TEXT') {
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
    setState((current) => {
      if (!current.textEditor) {
        return current
      }
      const updatedEditor = { ...current.textEditor, ...patch }
      if (updatedEditor.mode === 'edit') {
        const nodeId = updatedEditor.nodeId
        const existingDraft = current.drafts[nodeId] ?? { generation: 0, updatedAt: Date.now() }
        const nextDraft: CanvasNodeDraft = {
          ...existingDraft,
          generation: existingDraft.generation + 1,
          updatedAt: Date.now(),
          text: {
            name: updatedEditor.name,
            markdown: updatedEditor.markdown,
          },
        }
        return {
          ...current,
          textEditor: updatedEditor,
          drafts: {
            ...current.drafts,
            [nodeId]: nextDraft,
          },
        }
      }
      return { ...current, textEditor: updatedEditor }
    })
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
      const current = queueRef.current?.currentSnapshot() ?? snapshotQuery.data
      const node = current?.nodes.find((item) => item.id === editor.nodeId)
      if (!node) {
        return
      }
      const commands: CanvasCommandDTO[] = [
        {
          type: 'SET_NODE_RESOURCES',
          nodeId: editor.nodeId,
          expectedResourceIds: node.resources.map((r) => r.id),
          resources: [{ kind: 'TEXT', name: 'text', textContent: editor.markdown }],
        },
      ]
      if (node.name !== editor.name.trim()) {
        commands.push({
          type: 'RENAME_NODE',
          nodeId: editor.nodeId,
          expectedName: node.name,
          name: editor.name.trim(),
        })
      }
      const currentDraft = state.drafts[editor.nodeId]
      const ack: CanvasDraftAck[] = currentDraft
        ? [{ nodeId: editor.nodeId, field: 'text', generation: currentDraft.generation }]
        : []

      void executeCommands(commands, ack).then(() => {
        setState((current) => ({ ...current, textEditor: null }))
      }).catch(() => undefined)
      return
    }

    const alias = reserveNodeAlias(editor.name)
    void executeCommands([{
      type: 'CREATE_NODE',
      nodeId: crypto.randomUUID() as UUIDString,
      name: alias,
      transform: nextTransform(),
      resources: [{ kind: 'TEXT', name: 'text', textContent: editor.markdown }],
    }]).then(() => {
      setState((current) => ({ ...current, textEditor: null }))
    }).catch(() => undefined).finally(() => releaseNodeAlias(alias))
  }, [executeCommands, nextTransform, releaseNodeAlias, reserveNodeAlias, snapshotQuery.data, state.drafts, state.textEditor])

  const createFunctionNode = useCallback((outputKind: 'IMAGE' | 'VIDEO') => {
    const fnDef = modelsQuery.data?.find((item) =>
      item.outputs?.some((o) => o.kind === outputKind) && item.available !== false
    ) ?? modelsQuery.data?.find((item) =>
      item.outputs?.some((o) => o.kind === outputKind)
    )
    if (!fnDef || fnDef.available === false) {
      setToast(fnDef?.unavailableReason || `没有可用的${outputKind === 'IMAGE' ? '图片' : '视频'}模型`)
      return
    }
    const alias = reserveNodeAlias(outputKind === 'IMAGE' ? '图片生成' : '视频生成')
    const nodeId = crypto.randomUUID() as UUIDString
    void executeCommands([
      {
        type: 'CREATE_NODE',
        nodeId,
        name: alias,
        transform: nextTransform(),
        resources: [],
      },
      {
        type: 'SET_NODE_FUNCTION',
        nodeId,
        expectedFunction: null,
        function: {
          name: fnDef.name,
          args: { prompt: outputKind === 'IMAGE' ? '描述要生成的图片' : '描述要生成的视频' },
        },
      },
    ]).catch(() => undefined).finally(() => releaseNodeAlias(alias))
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
    const groupId = crypto.randomUUID() as UUIDString
    void executeCommands([
      {
        type: 'CREATE_GROUP',
        groupId,
        title: '分组',
        transform: { x: minX, y: minY, width: maxX - minX, height: maxY - minY },
      },
      ...members.map((node) => ({
        type: 'SET_NODE_GROUP' as const,
        nodeId: node.id,
        expectedGroupId: node.groupId,
        groupId,
      })),
    ]).catch(() => undefined)
  }, [
    executeCommands,
    setToast,
    snapshotQuery.data,
    state.positionDrafts,
    state.selectedIds,
  ])

  const ungroupGroup = useCallback((groupId: UUIDString) => {
    const memberNodes = (snapshotQuery.data?.nodes ?? [])
      .filter((node) => node.groupId === groupId)
    if (memberNodes.length === 0) {
      setToast('当前分组没有成员。')
      return
    }
    void executeCommands(
      memberNodes.map((node) => ({
        type: 'SET_NODE_GROUP',
        nodeId: node.id,
        expectedGroupId: groupId,
        groupId: null,
      })),
    ).catch(() => undefined)
  }, [executeCommands, setToast, snapshotQuery.data?.nodes])

  const renameGroup = useCallback((groupId: UUIDString, title: string) => {
    const normalized = title.trim()
    if (!normalized) {
      return
    }
    const group = snapshotQuery.data?.groups.find((g) => g.id === groupId)
    if (!group) {
      return
    }
    void executeCommands([{
      type: 'RENAME_GROUP',
      groupId,
      expectedTitle: group.title,
      title: normalized,
    }]).catch(() => undefined)
  }, [executeCommands, snapshotQuery.data?.groups])

  const deleteGroup = useCallback((groupId: UUIDString) => {
    const memberIds = (snapshotQuery.data?.nodes ?? [])
      .filter((n) => n.groupId === groupId)
      .map((n) => n.id)
    void executeCommands([
      ...memberIds.map((nodeId) => ({
        type: 'SET_NODE_GROUP' as const,
        nodeId,
        expectedGroupId: groupId,
        groupId: null,
      })),
      {
        type: 'DELETE_GROUP',
        groupId,
        expectedMemberNodeIds: memberIds,
      },
    ]).catch(() => undefined)
  }, [executeCommands, snapshotQuery.data?.nodes])

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
    const snapshot = snapshotQuery.data
    if (!snapshot) {
      return
    }
    const commands: CanvasCommandDTO[] = []
    for (const id of state.selectedIds) {
      const node = snapshot.nodes.find((n) => n.id === id)
      if (node) {
        commands.push({
          type: 'DELETE_NODE',
          nodeId: node.id,
          expectedResourceIds: node.resources.map((r) => r.id),
          expectedFunction: node.function ? { name: node.function.name, args: node.function.args } : null,
        })
      }
      const group = snapshot.groups.find((g) => g.id === id)
      if (group) {
        const members = snapshot.nodes.filter((n) => n.groupId === group.id).map((n) => n.id)
        for (const mId of members) {
          commands.push({ type: 'SET_NODE_GROUP', nodeId: mId, expectedGroupId: group.id, groupId: null })
        }
        commands.push({ type: 'DELETE_GROUP', groupId: group.id, expectedMemberNodeIds: members })
      }
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
  }, [executeCommands, snapshotQuery.data, state.selectedIds])

  // 派生引用维护：在目标节点的 function.args 中注入或移除引用
  const createLink = useCallback((sourceNodeId: UUIDString, targetNodeId: UUIDString) => {
    const snapshot = snapshotQuery.data
    const target = snapshot?.nodes.find((n) => n.id === targetNodeId)
    if (!target || !target.function) {
      return
    }
    const newArgs = {
      ...target.function.args,
      reference: { type: 'resource', nodeId: sourceNodeId, index: 0 },
    }
    void executeCommands([{
      type: 'SET_NODE_FUNCTION',
      nodeId: targetNodeId,
      expectedFunction: target.function,
      function: { name: target.function.name, args: newArgs },
    }]).catch(() => undefined)
  }, [executeCommands, snapshotQuery.data])

  const deleteLink = useCallback((_sourceNodeId: UUIDString, targetNodeId: UUIDString) => {
    const snapshot = snapshotQuery.data
    const target = snapshot?.nodes.find((n) => n.id === targetNodeId)
    if (!target || !target.function) {
      return
    }
    const newArgs = { ...target.function.args }
    delete newArgs.reference
    void executeCommands([{
      type: 'SET_NODE_FUNCTION',
      nodeId: targetNodeId,
      expectedFunction: target.function,
      function: { name: target.function.name, args: newArgs },
    }]).catch(() => undefined)
  }, [executeCommands, snapshotQuery.data])

  const deleteNode = useCallback((nodeId: UUIDString) => {
    const snapshot = snapshotQuery.data
    const node = snapshot?.nodes.find((n) => n.id === nodeId)
    void flushFunctionConfig(nodeId)
      .catch(() => undefined)
      .then(() => executeCommands([{
        type: 'DELETE_NODE',
        nodeId,
        expectedResourceIds: node ? node.resources.map((r) => r.id) : [],
        expectedFunction: node?.function ? { name: node.function.name, args: node.function.args } : null,
      }]))
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
  }, [executeCommands, flushFunctionConfig, snapshotQuery.data])

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

  // 权威快照叠加草稿层投影
  const projectedSnapshot = useMemo(() => {
    if (!snapshotQuery.data) {
      return null
    }
    const base = projectCanvasSnapshot(snapshotQuery.data)
    const resourceNodes = base.resourceNodes.map((node) =>
      overlayNodeWithDraft(node, state.drafts[node.id]),
    )
    return {
      ...base,
      resourceNodes,
    }
  }, [snapshotQuery.data, state.drafts])

  return {
    state,
    snapshot: snapshotQuery.data ?? null,
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
    resolveFunctionRun,
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
    dismissDraft,
    retryDraft,
    saveDraftAsNewNode,
    restoreDeletedDraftAsNewNode: saveDraftAsNewNode,
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
