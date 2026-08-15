import {
  Background,
  BackgroundVariant,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  SelectionMode,
  useNodesInitialized,
  useReactFlow,
  type Connection,
  type Edge,
  type NodeChange,
  type OnSelectionChangeParams,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent } from 'react'
import { CanvasAgentDock } from '@/features/canvas/agent/CanvasAgentDock'
import {
  CanvasContextMenu,
  type CanvasContextMenuState,
  type ContextMenuTarget,
} from '@/features/canvas/CanvasContextMenu'
import { CanvasGenerationPanel } from '@/features/canvas/CanvasGenerationPanel'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { CanvasTextEditor } from '@/features/canvas/CanvasTextEditor'
import { CanvasToolRail } from '@/features/canvas/CanvasToolRail'
import { CANVAS_THEME } from '@/features/canvas/canvas-theme'
import { projectCanvasSnapshot } from '@/features/canvas/domain'
import { extractPositionUpdates } from '@/features/canvas/node-position-changes'
import { canvasNodeTypes } from '@/features/canvas/nodes/CanvasNodeRenderers'
import { groupIdFromFlowId, projectEdges, projectNodes, type CanvasFlowNode } from '@/features/canvas/projection'
import type { CanvasPositionUpdate } from '@/features/canvas/types'
import {
  MAX_CANVAS_ZOOM,
  MIN_CANVAS_ZOOM,
} from '@/features/canvas/viewport-storage'
import { preserveCanvasWorldCenter } from '@/features/canvas/viewport-framing'
import type { UUIDString } from '@/shared/api/contracts/studio'
import { useI18n } from '@/shared/i18n'

const INITIAL_FIT = { padding: 0.18, maxZoom: 1.6, duration: 0 }
const PANEL_FIT = { padding: 0.08, maxZoom: 1.6, duration: 0 }
const FOCUS_SELECTION_FIT = { padding: 0.22, maxZoom: 1.8, duration: 0 }

function StageInner() {
  const runtime = useCanvasRuntime()
  const { t } = useI18n()
  const {
    state,
    snapshot: snapshotDTO,
    models,
    stageMetrics,
    nodeCallbacks,
    stageElementRef,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    setStageMetrics,
    setViewport,
    setSelection,
    moveNodes,
    commitTransforms,
    createLink,
    deleteLink,
    uploadFiles,
    initialFitPending,
    completeInitialFit,
    closeContextMenuRef,
  } = runtime
  const snapshot = useMemo(
    () => snapshotDTO ? projectCanvasSnapshot(snapshotDTO) : null,
    [snapshotDTO],
  )
  const containerRef = useRef<HTMLElement | null>(null)
  const lastViewportRef = useRef(state.viewport)
  const initialFitStartedRef = useRef(false)
  const mountedRef = useRef(true)
  const flowWidthRef = useRef<number | null>(null)
  const { fitView, getViewport, setViewport: setFlowViewport, zoomTo } = useReactFlow()
  const nodesInitialized = useNodesInitialized()

  const nodes = useMemo(
    () => snapshot
      ? projectNodes(snapshot, state.selectedIds, models, nodeCallbacks, state.positionDrafts)
      : [],
    [models, nodeCallbacks, snapshot, state.positionDrafts, state.selectedIds],
  )
  const edges = useMemo(
    () => snapshot ? projectEdges(snapshot.links, state.selectedLinks) : [],
    [snapshot, state.selectedLinks],
  )
  const selectedFunctionNode = useMemo(() => {
    if (!snapshot || state.selectedIds.length !== 1) {
      return null
    }
    return snapshot.resourceNodes.find((node) => (
      node.id === state.selectedIds[0] && Boolean(node.function)
    )) ?? null
  }, [snapshot, state.selectedIds])
  const selectedFunctionFlowNode = selectedFunctionNode
    ? nodes.find((node) => node.id === selectedFunctionNode.id)
    : null

  // 右键菜单：Stage 级单一 overlay，记录打开时的选区快照用于关闭判定。
  const [contextMenu, setContextMenu] = useState<CanvasContextMenuState | null>(null)
  const contextMenuSelectionRef = useRef<string[] | null>(null)
  const closeContextMenu = useCallback(() => {
    contextMenuSelectionRef.current = null
    setContextMenu(null)
  }, [])

  // Canvas surface 的全局 Escape（capture 相位消费）通过 controller 关闭全部
  // overlay；右键菜单状态在本组件，注册关闭回调供 closeOverlays 调用。
  useEffect(() => {
    closeContextMenuRef.current = closeContextMenu
    return () => {
      closeContextMenuRef.current = null
    }
  }, [closeContextMenu, closeContextMenuRef])

  const buildMenuTarget = useCallback((nodeIds: string[]): ContextMenuTarget | null => {
    if (!snapshot) {
      return null
    }
    if (nodeIds.length === 1) {
      const id = nodeIds[0] as string
      const groupId = groupIdFromFlowId(id)
      if (groupId) {
        const group = snapshot.groups.find((item) => item.id === groupId)
        return group ? { kind: 'group', group } : null
      }
      const node = snapshot.resourceNodes.find((item) => item.id === id)
      if (!node) {
        return null
      }
      return {
        kind: 'resource',
        node,
        model: node.function
          ? models.find((model) => model.key === node.function?.modelKey) ?? null
          : null,
      }
    }
    const selectedResources = nodeIds
      .map((id) => snapshot.resourceNodes.find((item) => item.id === id))
      .filter((node) => Boolean(node))
    const hasUngroupedResource = (
      selectedResources.length === nodeIds.length
      && selectedResources.every((node) => !node?.groupId)
    )
    return { kind: 'multi', nodeIds, hasUngroupedResource }
  }, [models, snapshot])

  const openContextMenu = useCallback((
    event: MouseEvent,
    nodeIds: string[],
  ) => {
    event.preventDefault()
    const target = buildMenuTarget(nodeIds)
    if (!target) {
      return
    }
    contextMenuSelectionRef.current = nodeIds
    setSelection(nodeIds)
    setContextMenu({ x: event.clientX, y: event.clientY, target })
  }, [buildMenuTarget, setSelection])

  const handleNodeContextMenu = useCallback((
    event: MouseEvent,
    flowNode: CanvasFlowNode,
  ) => {
    const isGroup = Boolean(groupIdFromFlowId(flowNode.id))
    const nodeIds = !isGroup && flowNode.selected && state.selectedIds.includes(flowNode.id)
      ? [...state.selectedIds]
      : [flowNode.id]
    openContextMenu(event, nodeIds)
  }, [openContextMenu, state.selectedIds])

  const handleSelectionContextMenu = useCallback((
    event: MouseEvent,
    selectedNodes: CanvasFlowNode[],
  ) => {
    const nodeIds = selectedNodes.map((node) => node.id)
    if (nodeIds.length === 0) {
      setContextMenu(null)
      return
    }
    openContextMenu(event, nodeIds)
  }, [openContextMenu])

  useEffect(() => {
    if (!contextMenu) {
      return
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setContextMenu(null)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [contextMenu])

  const publishMetrics = useCallback(() => {
    const element = containerRef.current
    if (!element) {
      return
    }
    const rect = element.getBoundingClientRect()
    setStageMetrics({
      width: rect.width || 960,
      height: rect.height || 640,
      dockTop: Math.max(120, rect.height - 16),
    })
  }, [setStageMetrics])

  useEffect(() => {
    publishMetrics()
    if (!containerRef.current || typeof ResizeObserver === 'undefined') {
      return
    }
    const observer = new ResizeObserver(publishMetrics)
    observer.observe(containerRef.current)
    return () => observer.disconnect()
  }, [publishMetrics, state.threadOpen])

  useEffect(() => {
    const next = state.viewport
    const previous = lastViewportRef.current
    if (
      Math.abs(next.x - previous.x) < 0.001
      && Math.abs(next.y - previous.y) < 0.001
      && Math.abs(next.zoom - previous.zoom) < 0.001
    ) {
      return
    }
    lastViewportRef.current = next
    void setFlowViewport(next)
  }, [setFlowViewport, state.viewport])

  // Chat panel 开关或拖拽调宽只平移 viewport，不得隐式改变用户的 zoom。
  useEffect(() => {
    const width = containerRef.current?.getBoundingClientRect().width ?? stageMetrics.width
    const previousWidth = flowWidthRef.current
    flowWidthRef.current = width
    if (previousWidth === null) {
      return
    }
    if (Math.abs(width - previousWidth) < 1) {
      return
    }
    const next = preserveCanvasWorldCenter(state.viewport, previousWidth, width)
    lastViewportRef.current = next
    void setFlowViewport(next)
    setViewport(next)
  }, [
    setFlowViewport,
    setViewport,
    stageMetrics.width,
    state.threadOpen,
    state.viewport,
  ])

  const syncViewport = useCallback(() => {
    const viewport = getViewport()
    const next = { x: viewport.x, y: viewport.y, zoom: viewport.zoom }
    lastViewportRef.current = next
    setViewport(next)
  }, [getViewport, setViewport])

  useEffect(() => {
    initialFitStartedRef.current = false
  }, [state.canvasId])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  useEffect(() => {
    if (
      !initialFitPending
      || !nodesInitialized
      || nodes.length === 0
      || initialFitStartedRef.current
    ) {
      return
    }
    initialFitStartedRef.current = true
    void fitView(state.threadOpen ? PANEL_FIT : INITIAL_FIT).then((fitted) => {
      if (!mountedRef.current) {
        return
      }
      if (!fitted) {
        initialFitStartedRef.current = false
        return
      }
      const fittedViewport = getViewport()
      const next = {
        x: fittedViewport.x,
        y: fittedViewport.y,
        zoom: fittedViewport.zoom,
      }
      lastViewportRef.current = next
      void setFlowViewport(next)
      setViewport(next)
      completeInitialFit()
    })
  }, [
    completeInitialFit,
    fitView,
    getViewport,
    initialFitPending,
    nodes.length,
    nodesInitialized,
    setFlowViewport,
    setViewport,
    state.threadOpen,
  ])

  useEffect(() => {
    fitViewRef.current = () => {
      void fitView(state.threadOpen ? PANEL_FIT : INITIAL_FIT).then((fitted) => {
        if (!fitted) {
          return
        }
        const fittedViewport = getViewport()
        const next = {
          x: fittedViewport.x,
          y: fittedViewport.y,
          zoom: fittedViewport.zoom,
        }
        lastViewportRef.current = next
        void setFlowViewport(next)
        setViewport(next)
      })
    }
    focusSelectionRef.current = () => {
      const selected = nodes.filter((node) => node.selected)
      void fitView({
        ...FOCUS_SELECTION_FIT,
        nodes: selected.length > 0 ? selected : undefined,
      }).then(syncViewport)
    }
    zoomRef.current = (zoom) => {
      void zoomTo(zoom).then(syncViewport)
    }
    return () => {
      fitViewRef.current = null
      focusSelectionRef.current = null
      zoomRef.current = null
    }
  }, [
    fitView,
    fitViewRef,
    focusSelectionRef,
    getViewport,
    nodes,
    setFlowViewport,
    setViewport,
    state.threadOpen,
    syncViewport,
    zoomRef,
    zoomTo,
  ])

  const handleNodesChange = useCallback((changes: NodeChange[]) => {
    if (!snapshot) {
      return
    }
    const positions = extractPositionUpdates(changes)
    const updates: CanvasPositionUpdate[] = []
    for (const position of positions) {
      if (position.id.startsWith('group:')) {
        const group = snapshot.groups.find((item) => `group:${item.id}` === position.id)
        if (group) {
          updates.push({
            id: position.id,
            kind: 'group',
            transform: {
              ...group.transform,
              x: position.x,
              y: position.y,
            },
          })
        }
        continue
      }
      const node = snapshot.resourceNodes.find((item) => item.id === position.id)
      if (node) {
        updates.push({
          id: position.id,
          kind: 'resource',
          transform: {
            ...node.transform,
            x: position.x,
            y: position.y,
          },
        })
      }
    }
    if (updates.length > 0) {
      moveNodes(updates)
      if (positions.some((position) => !position.dragging)) {
        commitTransforms()
      }
    }
  }, [commitTransforms, moveNodes, snapshot])

  const validConnection = useCallback((connection: Connection | Edge) => {
    if (!snapshot || !connection.source || !connection.target || connection.source === connection.target) {
      return false
    }
    const source = snapshot.resourceNodes.find((node) => node.id === connection.source)
    const target = snapshot.resourceNodes.find((node) => node.id === connection.target)
    return Boolean(source && source.resources.length > 0 && target?.function)
  }, [snapshot])

  const emitViewport = useCallback((viewport: { x: number; y: number; zoom: number }) => {
    const next = { x: viewport.x, y: viewport.y, zoom: viewport.zoom }
    lastViewportRef.current = next
    setViewport(next)
  }, [setViewport])

  const handleSelectionChange = useCallback((params: OnSelectionChangeParams) => {
    const nodeIds = params.nodes.map((node) => node.id)
    setSelection(
      nodeIds,
      params.edges.map((edge) => ({
        sourceNodeId: edge.source as UUIDString,
        targetNodeId: edge.target as UUIDString,
      })),
    )
    // 选区被外部改变（点击/框选/删除）时关闭右键菜单；右键打开时自身触发的
    // onSelectionChange 与快照一致，不关闭。
    const openedSelection = contextMenuSelectionRef.current
    if (
      contextMenuSelectionRef.current !== null
      && openedSelection
      && !sameIdList(openedSelection, nodeIds)
    ) {
      contextMenuSelectionRef.current = null
      setContextMenu(null)
    }
  }, [setSelection])

  return (
    <section
      className={`canvas-stage${state.threadOpen ? ' agent-panel-open' : ''}`}
      id="canvasStage"
      tabIndex={0}
      aria-label={t('canvas.stage.ariaLabel')}
      ref={(element) => {
        stageElementRef.current = element
      }}
      onDragOver={(event) => {
        if (event.dataTransfer.types.includes('Files')) {
          event.preventDefault()
          event.dataTransfer.dropEffect = 'copy'
        }
      }}
      onDrop={(event) => {
        if (event.dataTransfer.files.length > 0) {
          event.preventDefault()
          void uploadFiles(event.dataTransfer.files)
        }
      }}
    >
      <div
        className="canvas-flow-wrap"
        ref={(element) => {
          containerRef.current = element
        }}
      >
        <div className="canvas-flow">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={canvasNodeTypes}
            minZoom={MIN_CANVAS_ZOOM}
            maxZoom={MAX_CANVAS_ZOOM}
            defaultViewport={state.viewport}
            selectionOnDrag
            selectionMode={SelectionMode.Partial}
            panOnDrag={[1, 2]}
            nodesDraggable
            panActivationKeyCode="Space"
            panOnScroll
            zoomOnScroll={false}
            zoomOnPinch
            zoomOnDoubleClick={false}
            zoomActivationKeyCode={['Meta', 'Control']}
            multiSelectionKeyCode="Shift"
            deleteKeyCode={null}
            nodesConnectable
            edgesFocusable
            edgesReconnectable={false}
            elementsSelectable
            isValidConnection={validConnection}
            onConnect={(connection) => {
              if (connection.source && connection.target && validConnection(connection)) {
                const source = snapshot?.resourceNodes.find((node) => node.id === connection.source)
                const target = snapshot?.resourceNodes.find((node) => node.id === connection.target)
                if (source && target) {
                  createLink(source.id, target.id)
                }
              }
            }}
            onEdgesDelete={(deleted) => {
              for (const edge of deleted) {
                const link = snapshot?.links.find((item) => (
                  item.sourceNodeId === edge.source && item.targetNodeId === edge.target
                ))
                if (link) {
                  deleteLink(link.sourceNodeId, link.targetNodeId)
                }
              }
            }}
            onNodesChange={handleNodesChange}
            onNodeDragStop={() => commitTransforms()}
            onMove={(_event, viewport) => {
              emitViewport(viewport)
              setContextMenu(null)
            }}
            onMoveEnd={(_event, viewport) => emitViewport(viewport)}
            onSelectionChange={handleSelectionChange}
            onNodeContextMenu={handleNodeContextMenu}
            onSelectionContextMenu={handleSelectionContextMenu}
            onPaneContextMenu={(event) => {
              event.preventDefault()
              contextMenuSelectionRef.current = null
              setContextMenu(null)
            }}
            onNodeClick={(event, node) => {
              if (!event.shiftKey) {
                setSelection([node.id])
                if (
                  node.data.kind === 'resource'
                  && !node.data.node.function
                  && node.data.node.resources[0]?.kind === 'TEXT'
                ) {
                  runtime.editTextNode(node.data.node)
                }
              }
            }}
            onPaneClick={() => {
              contextMenuSelectionRef.current = null
              setContextMenu(null)
              setSelection([])
            }}
            proOptions={{ hideAttribution: true }}
          >
            <Background
              id="canvas-dots"
              variant={BackgroundVariant.Dots}
              gap={18}
              size={0.75}
              color={CANVAS_THEME.stageDot}
              bgColor={CANVAS_THEME.stageBg}
            />
            {!selectedFunctionNode ? (
              <MiniMap
                className="canvas-minimap"
                pannable
                zoomable
                ariaLabel={t('canvas.stage.minimap')}
                bgColor={CANVAS_THEME.stageBg}
                maskColor="rgba(13,15,14,0.55)"
                nodeColor={CANVAS_THEME.minimapNode}
                style={{ width: 120, height: 78 }}
              />
            ) : null}
          </ReactFlow>
        </div>

        {snapshot && selectedFunctionNode ? (
          <CanvasGenerationPanel
            key={selectedFunctionNode.id}
            snapshot={snapshot}
            node={selectedFunctionNode}
            anchor={selectedFunctionFlowNode ? {
              node: {
                ...selectedFunctionNode.transform,
                x: selectedFunctionFlowNode.position.x,
                y: selectedFunctionFlowNode.position.y,
              },
              viewport: state.viewport,
              stage: stageMetrics,
            } : undefined}
          />
        ) : null}

        <CanvasToolRail />
      </div>

      <CanvasAgentDock />
      <CanvasTextEditor />
      {contextMenu ? (
        <CanvasContextMenu
          key={contextMenuTargetKey(contextMenu.target)}
          state={contextMenu}
          onClose={closeContextMenu}
        />
      ) : null}
    </section>
  )
}

function contextMenuTargetKey(target: ContextMenuTarget): string {
  if (target.kind === 'resource') {
    return `resource:${target.node.id}`
  }
  if (target.kind === 'group') {
    return `group:${target.group.id}`
  }
  return `multi:${target.nodeIds.join(',')}`
}

function sameIdList(left: string[], right: string[]): boolean {
  if (left.length !== right.length) {
    return false
  }
  const rightIds = new Set(right)
  return left.every((id) => rightIds.has(id))
}

export function CanvasStage() {
  return (
    <ReactFlowProvider>
      <StageInner />
    </ReactFlowProvider>
  )
}
