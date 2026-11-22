import {
  Background,
  BackgroundVariant,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  SelectionMode,
  useNodesInitialized,
  useReactFlow,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useCallback, useEffect, useId, useMemo, useRef } from 'react'
import { CanvasContextMenu } from '@/features/canvas/CanvasContextMenu'
import { CanvasGenerationPanel } from '@/features/canvas/CanvasGenerationPanel'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { CanvasTextEditor } from '@/features/canvas/CanvasTextEditor'
import { CanvasToolRail } from '@/features/canvas/CanvasToolRail'
import { projectCanvasSnapshot } from '@/features/canvas/domain'
import { PlusIcon } from '@/features/canvas/icons'
import { canvasNodeTypes } from '@/features/canvas/nodes/CanvasNodeRenderers'
import { projectEdges, projectNodes } from '@/features/canvas/projection'
import { useCanvasStageContextMenu } from '@/features/canvas/useCanvasStageContextMenu'
import { useCanvasStageGraph } from '@/features/canvas/useCanvasStageGraph'
import { useCanvasStageViewport } from '@/features/canvas/useCanvasStageViewport'
import {
  MAX_CANVAS_ZOOM,
  MIN_CANVAS_ZOOM,
} from '@/features/canvas/viewport-storage'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'

/** 画布舞台：React Flow 视图、右键菜单、Function 工作台、文本编辑器与工具轨的装配点。 */
function StageInner() {
  const runtime = useCanvasRuntime()
  const { t } = useI18n()
  const {
    state,
    snapshot: snapshotDTO,
    projectedSnapshot,
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
    toggleAddMenu,
    initialFitPending,
    completeInitialFit,
    closeContextMenuRef,
  } = runtime
  const snapshot = useMemo(
    () => projectedSnapshot ?? (snapshotDTO ? projectCanvasSnapshot(snapshotDTO) : null),
    [projectedSnapshot, snapshotDTO],
  )
  const containerRef = useRef<HTMLElement | null>(null)
  const addMenuId = useId()
  const { fitView, getViewport, setViewport: setFlowViewport, zoomTo } = useReactFlow()
  const nodesInitialized = useNodesInitialized()

  const nodes = useMemo(
    () => snapshot
      ? projectNodes(snapshot, state.selectedIds, models, nodeCallbacks, state.positionDrafts)
      : [],
    [models, nodeCallbacks, snapshot, state.positionDrafts, state.selectedIds],
  )
  // 空画布引导只针对真正没有节点的画布；有节点后由舞台本身表达内容。
  const isEmptyCanvas = snapshot !== null && snapshot.resourceNodes.length === 0
  const edges = useMemo(
    () => snapshot ? projectEdges(snapshot.references, state.selectedLinks) : [],
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
  // 缩略图只在有节点时出现：零节点的画布没有可导航的内容，Function 面板会取代它。
  const showMinimap = nodes.length > 0 && !selectedFunctionNode

  const {
    contextMenu,
    contextMenuKey,
    closeContextMenu,
    openNodeContextMenu,
    openSelectionContextMenu,
    handleSelectionChange: handleSelectionChangeMenu,
  } = useCanvasStageContextMenu({
    snapshot,
    models,
    selectedIds: state.selectedIds,
    setSelection,
    closeContextMenuRef,
  })

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
  }, [publishMetrics])

  const { emitViewport } = useCanvasStageViewport({
    canvasId: state.canvasId,
    viewport: state.viewport,
    stageWidth: stageMetrics.width,
    containerRef,
    fitView,
    getViewport,
    zoomTo,
    setFlowViewport,
    setViewport,
    nodes,
    nodesInitialized,
    initialFitPending,
    completeInitialFit,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
  })

  const graph = useCanvasStageGraph({
    snapshot,
    models,
    moveNodes,
    commitTransforms,
    createLink,
    deleteLink,
    editTextNode: runtime.editTextNode,
    setSelection,
    handleSelectionChangeMenu,
    closeContextMenu,
  })

  return (
    <section
      className="canvas-stage"
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
            isValidConnection={graph.validConnection}
            onConnect={graph.handleConnect}
            onEdgesDelete={graph.handleEdgesDelete}
            onNodesChange={graph.handleNodesChange}
            onNodeDragStop={() => commitTransforms()}
            onMove={(_event, viewport) => {
              emitViewport(viewport)
              closeContextMenu()
            }}
            onMoveEnd={(_event, viewport) => emitViewport(viewport)}
            onSelectionChange={graph.handleSelectionChange}
            onNodeContextMenu={openNodeContextMenu}
            onSelectionContextMenu={openSelectionContextMenu}
            onPaneContextMenu={graph.handlePaneContextMenu}
            onNodeClick={graph.handleNodeClick}
            onPaneClick={graph.handlePaneClick}
            proOptions={{ hideAttribution: true }}
          >
            <Background
              id="canvas-dots"
              variant={BackgroundVariant.Dots}
              gap={18}
              size={0.75}
              color="var(--stage-dot)"
              bgColor="var(--stage-bg)"
            />
            {showMinimap ? (
              <MiniMap
                className="canvas-minimap"
                pannable
                zoomable
                ariaLabel={t('canvas.stage.minimap')}
                bgColor="var(--stage-bg)"
                maskColor="var(--surface-glass)"
                nodeColor="var(--stage-node)"
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

        {isEmptyCanvas ? (
          <div className="canvas-empty-guidance" role="note">
            <div className="canvas-empty-guidance-body">
              <strong className="canvas-empty-guidance-title">{t('canvas.empty.title')}</strong>
              <p className="canvas-empty-guidance-description">{t('canvas.empty.description')}</p>
              <Button
                className="canvas-empty-guidance-action"
                aria-expanded={state.addMenuOpen}
                aria-controls={addMenuId}
                onClick={toggleAddMenu}
              >
                <PlusIcon />
                {t('canvas.add.ariaLabel')}
              </Button>
            </div>
          </div>
        ) : null}

        <CanvasToolRail menuId={addMenuId} />
      </div>

      <CanvasTextEditor />
      {contextMenu ? (
        <CanvasContextMenu
          key={contextMenuKey}
          state={contextMenu}
          onClose={closeContextMenu}
        />
      ) : null}
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
