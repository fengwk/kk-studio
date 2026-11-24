import {
  Background, BackgroundVariant, ReactFlow, ReactFlowProvider,
  useReactFlow, useStore,
  type Node, type NodeChange, type NodeProps, type XYPosition,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { Minus, Plus, Scan, Type } from 'lucide-react'
import { useEffect, useId, useRef, useState, useSyncExternalStore } from 'react'
import '@/features/canvas/canvas.css'
import { CanvasNodeContainer } from '@/features/canvas/nodes/CanvasNodeContainer'
import { MAX_CANVAS_ZOOM, MIN_CANVAS_ZOOM } from '@/features/canvas/viewport-storage'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { getLocalCanvas, TEXT_NODE_HEIGHT, TEXT_NODE_WIDTH } from '../mock/canvas'
import './canvas-preview.css'

export type CanvasResource = { id: string; title: string; sample: boolean }
type TextFlowNode = Node<{ name: string; text: string; edit: (id: string, text: string) => void }, 'text'>

function TextNode({ id, data }: NodeProps<TextFlowNode>) {
  return (
    <CanvasNodeContainer icon={<Type />} typeLabel="文字" name={data.name} bodyClassName="resource-node-body">
      <TextArea
        className="canvas-preview-text nodrag nopan nowheel"
        aria-label={`${data.name}的文字内容`}
        placeholder="输入文字…"
        value={data.text}
        onChange={(event) => data.edit(id, event.target.value)}
      />
    </CanvasNodeContainer>
  )
}

const nodeTypes = { text: TextNode }
const fitOptions = { padding: 0.15, maxZoom: 1 }

function CanvasViewport({ canvas }: { canvas: CanvasResource }) {
  const localCanvas = getLocalCanvas(canvas.id, canvas.sample)
  const notes = useSyncExternalStore(localCanvas.subscribe, localCanvas.getSnapshot)
  const [selected, setSelected] = useState<Set<string>>(() => new Set())
  const stage = useRef<HTMLDivElement>(null)
  const flowId = useId()
  const flow = useReactFlow<TextFlowNode>()
  const zoom = useStore((state) => state.transform[2])

  useEffect(() => {
    if (!stage.current) return
    let last: DOMRectReadOnly | null = null
    const observer = new ResizeObserver(([entry]) => {
      const rect = entry.contentRect
      if (!rect.width || !rect.height) return
      if (last) {
        // Keep the same world point at the center when a pane changes size.
        const viewport = flow.getViewport()
        void flow.setViewport({ ...viewport, x: viewport.x + (rect.width - last.width) / 2,
          y: viewport.y + (rect.height - last.height) / 2 })
      }
      last = rect
    })
    observer.observe(stage.current)
    return () => observer.disconnect()
  }, [flow])

  function addText() {
    const rect = stage.current?.getBoundingClientRect()
    if (!rect) return
    const center = flow.screenToFlowPosition({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 })
    const { id, position } = localCanvas.addText({ x: center.x - TEXT_NODE_WIDTH / 2, y: center.y - TEXT_NODE_HEIGHT / 2 })
    setSelected(new Set([id]))
    void flow.setCenter(position.x + TEXT_NODE_WIDTH / 2, position.y + TEXT_NODE_HEIGHT / 2, { zoom })
  }

  function changeNodes(changes: NodeChange<TextFlowNode>[]) {
    const positions = new Map<string, XYPosition>()
    for (const change of changes) {
      if (change.type === 'position' && change.position) positions.set(change.id, change.position)
    }
    if (positions.size) localCanvas.moveNodes(positions)
    if (changes.some((change) => change.type === 'select')) {
      setSelected((current) => {
        const next = new Set(current)
        for (const change of changes) {
          if (change.type !== 'select') continue
          if (change.selected) next.add(change.id)
          else next.delete(change.id)
        }
        return next
      })
    }
  }

  return (
    <section className="canvas-feature canvas-resource-preview" aria-label={canvas.title}
      data-note="同一画布在不同窗格共享文字和位置，各自缩放与平移。离线编辑只保留到刷新前。">
      <div className="canvas-preview-toolbar" role="group" aria-label="画布工具">
        <Button size="compact" onClick={addText}><Plus aria-hidden="true" />添加文字</Button>
        <div className="canvas-preview-zoom" role="group" aria-label="缩放">
          <IconButton size="compact" label="缩小" disabled={zoom <= MIN_CANVAS_ZOOM} onClick={() => void flow.zoomOut()}><Minus aria-hidden="true" /></IconButton>
          <span className="canvas-preview-ratio">{Math.round(zoom * 100)}%</span>
          <IconButton size="compact" label="放大" disabled={zoom >= MAX_CANVAS_ZOOM} onClick={() => void flow.zoomIn()}><Plus aria-hidden="true" /></IconButton>
          <IconButton size="compact" label="适应内容" disabled={!notes.length} onClick={() => void flow.fitView(fitOptions)}><Scan aria-hidden="true" /></IconButton>
        </div>
      </div>
      <div className="canvas-stage" ref={stage}>
        <ReactFlow<TextFlowNode>
          id={flowId}
          nodes={notes.map(({ id, name, text, position }) => ({
            id, type: 'text', position, width: TEXT_NODE_WIDTH, height: TEXT_NODE_HEIGHT,
            data: { name, text, edit: localCanvas.editText }, ariaLabel: name,
            dragHandle: '.canvas-node-header', selected: selected.has(id),
          }))}
          nodeTypes={nodeTypes}
          onNodesChange={changeNodes}
          fitView={notes.length > 0}
          fitViewOptions={fitOptions}
          minZoom={MIN_CANVAS_ZOOM}
          maxZoom={MAX_CANVAS_ZOOM}
          deleteKeyCode={null}
          nodesConnectable={false}
          zoomOnDoubleClick={false}
          aria-label="画布编辑区"
          proOptions={{ hideAttribution: true }}
        >
          <Background id={flowId} variant={BackgroundVariant.Dots} gap={18} size={0.75} color="var(--stage-dot)" bgColor="var(--stage-bg)" />
        </ReactFlow>
        {notes.length === 0 && <div className="canvas-preview-empty"><StateBlock title="添加文字开始" /></div>}
      </div>
    </section>
  )
}

/** 同一画布可挂载到多个窗格，视角独立，文档共享。 */
export function CanvasResourceView({ canvas }: { canvas: CanvasResource }) {
  return <ReactFlowProvider key={canvas.id}><CanvasViewport canvas={canvas} /></ReactFlowProvider>
}
