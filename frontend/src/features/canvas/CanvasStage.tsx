import {
  Background,
  BackgroundVariant,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  SelectionMode,
  useReactFlow,
  type NodeChange,
  type OnSelectionChangeParams,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useCallback, useEffect, useMemo, useRef } from 'react'
import { CanvasAgentDock } from '@/features/canvas/agent/CanvasAgentDock'
import { CanvasGenerationWorkbench } from '@/features/canvas/CanvasGenerationWorkbench'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { CANVAS_THEME } from '@/features/canvas/canvas-theme'
import { MAX_ZOOM, MIN_ZOOM } from '@/features/canvas/data'
import { computeSelectionToolbarPosition, selectionBounds, viewportsEqual } from '@/features/canvas/geometry'
import { extractPositionUpdates } from '@/features/canvas/node-position-changes'
import { canvasNodeTypes } from '@/features/canvas/nodes/CanvasNodeRenderers'
import { projectEdges, projectNodes } from '@/features/canvas/projection'
import { useI18n } from '@/shared/i18n'

function StageInner() {
  const runtime = useCanvasRuntime()
  const { t } = useI18n()
  const {
    state,
    stageMetrics,
    stageElementRef,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    setStageMetrics,
    setViewport,
    setSelection,
    moveNodes,
    activateGenerator,
    setToast,
    setContextMode,
    openThread,
    focusAgentDock,
    consumePendingFit,
  } = runtime

  const containerRef = useRef<HTMLElement | null>(null)
  const dockWrapRef = useRef<HTMLDivElement | null>(null)
  const lastEmittedViewportRef = useRef(state.viewport)
  const { fitView, getViewport, zoomTo, setViewport: setFlowViewport } = useReactFlow()
  const nodes = useMemo(() => projectNodes(state.nodes, state.selectedIds), [state.nodes, state.selectedIds])
  const edges = useMemo(() => projectEdges(state.links), [state.links])

  const publishMetrics = useCallback(() => {
    const el = containerRef.current
    if (!el) {
      return
    }
    const rect = el.getBoundingClientRect()
    const dock = dockWrapRef.current
    const dockTop = dock
      ? Math.max(0, dock.getBoundingClientRect().top - rect.top)
      : Math.max(0, rect.height - 96)
    setStageMetrics({
      width: rect.width || el.clientWidth || 960,
      height: rect.height || el.clientHeight || 640,
      dockTop,
    })
  }, [setStageMetrics])

  useEffect(() => {
    publishMetrics()
    const el = containerRef.current
    if (!el || typeof ResizeObserver === 'undefined') {
      return
    }
    const observer = new ResizeObserver(() => publishMetrics())
    observer.observe(el)
    if (dockWrapRef.current) {
      observer.observe(dockWrapRef.current)
    }
    return () => observer.disconnect()
  }, [publishMetrics, state.threadOpen, state.addMenuOpen, state.messages.length])

  // Apply only programmatic domain viewport changes; ignore equal values to prevent RF update loops.
  useEffect(() => {
    if (viewportsEqual(state.viewport, lastEmittedViewportRef.current)) {
      return
    }
    lastEmittedViewportRef.current = state.viewport
    void setFlowViewport({
      x: state.viewport.x,
      y: state.viewport.y,
      zoom: state.viewport.scale,
    })
  }, [setFlowViewport, state.viewport])

  useEffect(() => {
    const syncFromFlow = () => {
      const viewport = getViewport()
      const next = { x: viewport.x, y: viewport.y, scale: viewport.zoom }
      lastEmittedViewportRef.current = next
      setViewport(next)
    }
    fitViewRef.current = () => {
      void fitView({ padding: 0.18, duration: 0 }).then(syncFromFlow)
    }
    focusSelectionRef.current = () => {
      const selected = nodes.filter((node) => node.selected)
      const task = selected.length === 0
        ? fitView({ padding: 0.18, duration: 0 })
        : fitView({ nodes: selected, padding: 0.25, duration: 0 })
      void task.then(syncFromFlow)
    }
    zoomRef.current = (scale: number) => {
      void zoomTo(scale).then(syncFromFlow)
    }
    consumePendingFit()
    return () => {
      fitViewRef.current = null
      focusSelectionRef.current = null
      zoomRef.current = null
    }
  }, [
    consumePendingFit,
    fitView,
    fitViewRef,
    focusSelectionRef,
    getViewport,
    nodes,
    setViewport,
    zoomRef,
    zoomTo,
  ])

  const emitViewport = useCallback((viewport: { x: number; y: number; zoom: number }) => {
    const next = { x: viewport.x, y: viewport.y, scale: viewport.zoom }
    if (viewportsEqual(next, lastEmittedViewportRef.current)) {
      return
    }
    lastEmittedViewportRef.current = next
    setViewport(next)
  }, [setViewport])

  const handleSelectionChange = useCallback((params: OnSelectionChangeParams) => {
    setSelection(params.nodes.map((node) => node.id))
  }, [setSelection])

  // Controlled-node contract: apply RF position changes into domain coordinates during drag.
  const handleNodesChange = useCallback((changes: NodeChange[]) => {
    const updates = extractPositionUpdates(changes)
    if (updates.length > 0) {
      moveNodes(updates)
    }
  }, [moveNodes])

  const selectedNodes = useMemo(
    () => state.nodes.filter((node) => state.selectedIds.includes(node.id)),
    [state.nodes, state.selectedIds],
  )
  const toolbarPosition = useMemo(() => {
    const bounds = selectionBounds(selectedNodes)
    if (!bounds) {
      return null
    }
    return computeSelectionToolbarPosition({
      bounds,
      viewport: state.viewport,
      stageWidth: stageMetrics.width || 960,
    })
  }, [selectedNodes, stageMetrics.width, state.viewport])

  const stageClass = [
    'canvas-stage',
    state.tool === 'hand' ? 'hand-tool' : '',
  ].filter(Boolean).join(' ')

  return (
    <section
      className={stageClass}
      id="canvasStage"
      tabIndex={0}
      aria-label={t('canvas.stage.ariaLabel')}
      ref={(node) => {
        containerRef.current = node
        stageElementRef.current = node
      }}
    >
      <div className="canvas-flow">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={canvasNodeTypes}
          minZoom={MIN_ZOOM}
          maxZoom={MAX_ZOOM}
          defaultViewport={{ x: state.viewport.x, y: state.viewport.y, zoom: state.viewport.scale }}
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
          nodesConnectable={false}
          edgesFocusable={false}
          elementsSelectable={state.tool === 'select'}
          onNodesChange={handleNodesChange}
          onMove={(_event, viewport) => {
            emitViewport(viewport)
          }}
          onMoveEnd={(_event, viewport) => {
            emitViewport(viewport)
          }}
          onSelectionChange={handleSelectionChange}
          onNodeClick={(event, node) => {
            if (state.tool !== 'select') {
              return
            }
            if (event.shiftKey) {
              const exists = state.selectedIds.includes(node.id)
              setSelection(
                exists
                  ? state.selectedIds.filter((id) => id !== node.id)
                  : [...state.selectedIds, node.id],
              )
            } else {
              setSelection([node.id])
            }
            if (node.type === 'generator') {
              activateGenerator(node.id, false)
            }
          }}
          onPaneClick={() => {
            setSelection([])
            activateGenerator(null)
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

      {toolbarPosition && state.selectedIds.length > 0 ? (
        <div
          className="selection-toolbar"
          role="toolbar"
          aria-label={t('canvas.stage.selectionToolbar')}
          style={{ left: toolbarPosition.left, top: toolbarPosition.top }}
        >
          <button type="button" onClick={() => setToast(t('canvas.toast.stage.edit'))}>{t('canvas.stage.edit')}</button>
          <button
            type="button"
            onClick={() => {
              setContextMode('selection')
              if (state.messages.length > 0) {
                openThread()
              }
              focusAgentDock()
              setToast(t('canvas.toast.stage.agent'))
            }}
          >
            {t('canvas.stage.agent')}
          </button>
          <button
            type="button"
            onClick={() => {
              setContextMode('selection')
              setToast(t('canvas.toast.stage.addContext', { count: state.selectedIds.length }))
            }}
          >
            {t('canvas.stage.addContext')}
          </button>
          <span />
          <button
            type="button"
            aria-label={t('canvas.stage.moreObjects')}
            onClick={() => setToast(t('canvas.toast.stage.moreObjects'))}
          >
            ···
          </button>
        </div>
      ) : null}

      <CanvasGenerationWorkbench />

      <div className="canvas-hint">
        <kbd>Space</kbd>
        {' '}
        {t('canvas.stage.hint.pan')}
        {' '}
        ·
        {' '}
        <kbd>V / H / T</kbd>
        {' '}
        {t('canvas.stage.hint.tools')}
        {' '}
        ·
        {' '}
        <kbd>⌘ K</kbd>
        {' '}
        {t('canvas.stage.hint.invokeAgent')}
      </div>

      <div className="canvas-controls">
        <div className="zoom-controls" role="group" aria-label={t('canvas.stage.zoomControls')}>
          <button type="button" aria-label={t('canvas.stage.zoomOut')} onClick={() => zoomRef.current?.(Math.max(MIN_ZOOM, state.viewport.scale / 1.15))}>−</button>
          <button type="button" aria-label={t('canvas.stage.fitAll')} title={t('canvas.stage.fitTitle')} onClick={() => fitViewRef.current?.()}>⊙</button>
          <button type="button" title={t('canvas.stage.zoom100Title')} onClick={() => zoomRef.current?.(1)}>100%</button>
          <button type="button" aria-label={t('canvas.stage.zoomIn')} onClick={() => zoomRef.current?.(Math.min(MAX_ZOOM, state.viewport.scale * 1.15))}>＋</button>
        </div>
      </div>

      <CanvasAgentDock dockWrapRef={dockWrapRef} />
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
