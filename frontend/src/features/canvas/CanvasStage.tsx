import {
  Background,
  BackgroundVariant,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  SelectionMode,
  useReactFlow,
  type Connection,
  type Edge,
  type NodeChange,
  type OnSelectionChangeParams,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useCallback, useEffect, useMemo, useRef } from 'react'
import { CanvasAgentDock } from '@/features/canvas/agent/CanvasAgentDock'
import { CanvasGenerationPanel } from '@/features/canvas/CanvasGenerationPanel'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { CanvasTextEditor } from '@/features/canvas/CanvasTextEditor'
import { CANVAS_THEME } from '@/features/canvas/canvas-theme'
import { projectCanvasSnapshot } from '@/features/canvas/domain'
import { extractPositionUpdates } from '@/features/canvas/node-position-changes'
import { canvasNodeTypes } from '@/features/canvas/nodes/CanvasNodeRenderers'
import { projectEdges, projectNodes } from '@/features/canvas/projection'
import type { CanvasPositionUpdate } from '@/features/canvas/types'
import {
  MAX_CANVAS_ZOOM,
  MIN_CANVAS_ZOOM,
} from '@/features/canvas/viewport-storage'
import type { DecimalString } from '@/shared/api/contracts/studio'
import { useI18n } from '@/shared/i18n'

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
    setTool,
    createLink,
    deleteLink,
    deleteSelection,
    createGroup,
    ungroupSelection,
    uploadFiles,
    focusAgentDock,
  } = runtime
  const snapshot = useMemo(
    () => snapshotDTO ? projectCanvasSnapshot(snapshotDTO) : null,
    [snapshotDTO],
  )
  const containerRef = useRef<HTMLElement | null>(null)
  const dockWrapRef = useRef<HTMLDivElement | null>(null)
  const lastViewportRef = useRef(state.viewport)
  const { fitView, getViewport, setViewport: setFlowViewport, zoomTo } = useReactFlow()

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

  const publishMetrics = useCallback(() => {
    const element = containerRef.current
    if (!element) {
      return
    }
    const rect = element.getBoundingClientRect()
    const dockTop = dockWrapRef.current
      ? dockWrapRef.current.getBoundingClientRect().top - rect.top
      : rect.height - 96
    setStageMetrics({
      width: rect.width || 960,
      height: rect.height || 640,
      dockTop: Math.max(120, dockTop),
    })
  }, [setStageMetrics])

  useEffect(() => {
    publishMetrics()
    if (!containerRef.current || typeof ResizeObserver === 'undefined') {
      return
    }
    const observer = new ResizeObserver(publishMetrics)
    observer.observe(containerRef.current)
    if (dockWrapRef.current) {
      observer.observe(dockWrapRef.current)
    }
    return () => observer.disconnect()
  }, [publishMetrics, state.threadOpen, state.addMenuOpen])

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

  useEffect(() => {
    const sync = () => {
      const viewport = getViewport()
      const next = { x: viewport.x, y: viewport.y, zoom: viewport.zoom }
      lastViewportRef.current = next
      setViewport(next)
    }
    fitViewRef.current = () => {
      void fitView({ padding: 0.16, duration: 0 }).then(sync)
    }
    focusSelectionRef.current = () => {
      const selected = nodes.filter((node) => node.selected)
      void fitView({
        nodes: selected.length > 0 ? selected : undefined,
        padding: 0.22,
        duration: 0,
      }).then(sync)
    }
    zoomRef.current = (zoom) => {
      void zoomTo(zoom).then(sync)
    }
    return () => {
      fitViewRef.current = null
      focusSelectionRef.current = null
      zoomRef.current = null
    }
  }, [fitView, fitViewRef, focusSelectionRef, getViewport, nodes, setViewport, zoomRef, zoomTo])

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

  return (
    <section
      className={`canvas-stage ${state.tool === 'hand' ? 'hand-tool' : ''}`}
      id="canvasStage"
      tabIndex={0}
      aria-label={t('canvas.stage.ariaLabel')}
      ref={(element) => {
        containerRef.current = element
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
      <div className="canvas-flow">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={canvasNodeTypes}
          minZoom={MIN_CANVAS_ZOOM}
          maxZoom={MAX_CANVAS_ZOOM}
          defaultViewport={state.viewport}
          selectionOnDrag={state.tool === 'select'}
          selectionMode={SelectionMode.Partial}
          panOnDrag={state.tool === 'hand' ? true : [1, 2]}
          nodesDraggable={state.tool === 'select'}
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
          elementsSelectable={state.tool === 'select'}
          onlyRenderVisibleElements
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
          onMove={(_event, viewport) => emitViewport(viewport)}
          onMoveEnd={(_event, viewport) => emitViewport(viewport)}
          onSelectionChange={(params: OnSelectionChangeParams) => {
            setSelection(
              params.nodes.map((node) => node.id),
              params.edges.map((edge) => ({
                sourceNodeId: edge.source as DecimalString,
                targetNodeId: edge.target as DecimalString,
              })),
            )
          }}
          onNodeClick={(event, node) => {
            if (!event.shiftKey) {
              setSelection([node.id])
            }
          }}
          onPaneClick={() => {
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
          <MiniMap
            className="canvas-minimap"
            pannable
            zoomable
            ariaLabel={t('canvas.stage.minimap')}
            maskColor="rgba(13,15,14,0.55)"
            nodeColor={CANVAS_THEME.minimapNode}
            style={{ width: 120, height: 78 }}
          />
        </ReactFlow>
      </div>

      {state.selectedIds.length > 0 || state.selectedLinks.length > 0 ? (
        <div className="selection-toolbar" role="toolbar" aria-label="选区操作">
          {state.selectedIds.length > 0 ? (
            <>
              <button type="button" onClick={createGroup}>分组</button>
              <button type="button" onClick={ungroupSelection}>解散 / 移出分组</button>
              <button type="button" onClick={focusAgentDock}>交给 Agent</button>
            </>
          ) : null}
          <button type="button" className="danger" onClick={deleteSelection}>删除</button>
        </div>
      ) : null}

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

      <div className="canvas-hint">
        <kbd>Space</kbd>
        {' 平移 · '}
        <kbd>V / H / T</kbd>
        {' 工具 · '}
        <kbd>⌘ K</kbd>
        {' Agent'}
      </div>

      <div className="canvas-controls">
        <div className="zoom-controls" role="group" aria-label={t('canvas.stage.zoomControls')}>
          <button type="button" aria-label={t('canvas.stage.zoomOut')} onClick={() => zoomRef.current?.(Math.max(MIN_CANVAS_ZOOM, state.viewport.zoom / 1.15))}>−</button>
          <button type="button" aria-label={t('canvas.stage.fitAll')} onClick={() => fitViewRef.current?.()}>⊙</button>
          <button type="button" onClick={() => zoomRef.current?.(1)}>100%</button>
          <button type="button" aria-label={t('canvas.stage.zoomIn')} onClick={() => zoomRef.current?.(Math.min(MAX_CANVAS_ZOOM, state.viewport.zoom * 1.15))}>＋</button>
          <button type="button" aria-pressed={state.tool === 'select'} onClick={() => setTool('select')}>V</button>
          <button type="button" aria-pressed={state.tool === 'hand'} onClick={() => setTool('hand')}>H</button>
        </div>
      </div>

      <CanvasAgentDock dockWrapRef={dockWrapRef} />
      <CanvasTextEditor />
    </section>
  )
}

export function CanvasStage() {
  return (
    <ReactFlowProvider>
      <StageInner />
    </ReactFlowProvider>
  )
}
