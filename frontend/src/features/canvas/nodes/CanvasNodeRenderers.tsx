/* eslint-disable react-refresh/only-export-components */
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import { GENERATION_PROFILES, RUN_STEPS } from '@/features/canvas/data'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasFlowNodeData } from '@/features/canvas/projection'
import type {
  AgentRunNode,
  CanvasNode,
  ContentNode,
  FrameNode,
  GeneratorNode,
  ResultNode,
} from '@/features/canvas/types'

export type CanvasFlowNode = Node<CanvasFlowNodeData, CanvasNode['type']>

/** Invisible connection anchors so React Flow can draw domain Links (error #008 fix). */
function NodeHandles() {
  return (
    <>
      <Handle
        type="target"
        position={Position.Left}
        id="in"
        className="canvas-handle"
        isConnectable={false}
        aria-hidden="true"
        tabIndex={-1}
      />
      <Handle
        type="source"
        position={Position.Right}
        id="out"
        className="canvas-handle"
        isConnectable={false}
        aria-hidden="true"
        tabIndex={-1}
      />
    </>
  )
}

function NodeLabel({ icon, text }: { icon: string; text: string }) {
  return (
    <div className="node-label">
      <span className="type-icon">{icon}</span>
      {text}
    </div>
  )
}

function NodeDetails({ title, copy }: { title: string; copy?: string }) {
  return (
    <>
      <div className="node-title">{title}</div>
      {copy ? <div className="node-copy">{copy}</div> : null}
    </>
  )
}

function FrameNodeView({ data }: { data: FrameNode }) {
  return (
    <div className="canvas-node node-frame">
      <NodeHandles />
      <div className="frame-title">
        <span />
        {data.title}
        {' '}
        <em>
          ·
          {data.subtitle}
        </em>
      </div>
    </div>
  )
}

function WebNodeView({ data }: { data: ContentNode }) {
  return (
    <div className="canvas-node node-web">
      <NodeHandles />
      <div className="web-strip" />
      <div className="node-content">
        <NodeLabel icon="⌁" text="Web reference" />
        <NodeDetails title={data.title} copy={data.copy} />
        <div className="node-footer">
          <span>{data.meta}</span>
          <span>↗</span>
        </div>
      </div>
    </div>
  )
}

function ImageNodeView({ data }: { data: ContentNode }) {
  return (
    <div className="canvas-node node-image">
      <NodeHandles />
      <div className="node-content">
        <div className="image-preview" />
        <NodeLabel icon="◒" text="Image reference" />
        <NodeDetails title={data.title} copy={data.copy} />
        <div className="node-footer">
          <span>{data.meta}</span>
          <span>···</span>
        </div>
      </div>
    </div>
  )
}

function FileNodeView({ data }: { data: ContentNode }) {
  return (
    <div className="canvas-node node-file">
      <NodeHandles />
      <div className="node-content">
        <span className="file-thumb">PDF</span>
        <NodeLabel icon="▤" text="File" />
        <NodeDetails title={data.title} copy={data.copy} />
        <div className="node-footer">
          <span>{data.meta}</span>
          <span>↗</span>
        </div>
      </div>
    </div>
  )
}

function TextNodeView({ data }: { data: ContentNode }) {
  return (
    <div className="canvas-node node-text">
      <NodeHandles />
      <div className="node-content">
        <NodeLabel icon="T" text={data.meta || 'Text'} />
        <NodeDetails title={data.title} copy={data.copy} />
      </div>
    </div>
  )
}

function GeneratorPreview({ node }: { node: GeneratorNode }) {
  if (node.generationMode === 'text') {
    return (
      <div className={`generator-preview generator-preview-text ${node.status}`}>
        {node.status === 'generated' ? (
          <>
            <p>{node.copy}</p>
            <blockquote>“</blockquote>
          </>
        ) : (
          <>
            <i />
            <i />
            <i />
            <blockquote>“</blockquote>
          </>
        )}
      </div>
    )
  }
  if (node.generationMode === 'image') {
    return (
      <div className={`generator-preview generator-preview-image ${node.status}`}>
        <i />
        <i />
        <span>✦</span>
      </div>
    )
  }
  return (
    <div className={`generator-preview generator-preview-video ${node.status}`}>
      <i />
      <span className="generator-play">▶</span>
      <small>00:06</small>
    </div>
  )
}

function GeneratorNodeView({ data }: { data: GeneratorNode }) {
  const { activateGenerator } = useCanvasRuntime()
  const profile = GENERATION_PROFILES[data.generationMode]
  const status = data.status === 'generated' ? '已生成' : '草稿'
  return (
    <div
      className={`canvas-node node-generator ${data.generationMode}`}
      tabIndex={0}
      role="button"
      data-generation-mode={data.generationMode}
      data-generation-status={data.status}
      aria-label={`${data.title}，打开生成操作台`}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          activateGenerator(data.id, true)
        }
      }}
      onDoubleClick={() => activateGenerator(data.id, true)}
    >
      <NodeHandles />
      <div className="node-content">
        <GeneratorPreview node={data} />
        <div className="generator-node-head">
          <NodeLabel icon={profile.icon} text={profile.label} />
          <span className={`generator-state ${data.status}`}>{status}</span>
        </div>
        <NodeDetails title={data.title} copy={data.copy} />
        <div className="node-footer">
          <span>{data.status === 'generated' ? data.meta : data.capability}</span>
          <span>{data.status === 'generated' ? '✓' : '↗'}</span>
        </div>
      </div>
    </div>
  )
}

function RunNodeView({ data }: { data: AgentRunNode }) {
  const { runAction } = useCanvasRuntime()
  const running = data.status === 'running'
  const paused = data.status === 'paused'
  const statusText = running
    ? `运行中 · ${data.progress}/${data.total}`
    : paused
      ? `已暂停 · ${data.progress}/${data.total}`
      : `已完成 · ${data.progress}/${data.total}`
  const control = running ? '暂停' : paused ? '继续' : '重试'
  const action = running ? 'pause' : paused ? 'resume' : 'retry'
  return (
    <div className="canvas-node node-run">
      <NodeHandles />
      <div className="node-content">
        <div className="run-header">
          <NodeLabel icon="✦" text="Agent Run" />
          <span className="run-status">{statusText}</span>
        </div>
        <div className="node-title">{data.title}</div>
        <div className="run-steps">
          {RUN_STEPS.map((step, index) => {
            const done = index < data.progress
            const current = running && index === data.progress
            const marker = done ? '✓' : current ? '●' : '○'
            return (
              <div key={step} className={`run-step ${done ? 'done' : ''} ${current ? 'current' : ''}`}>
                <b>{marker}</b>
                {step}
              </div>
            )
          })}
        </div>
        <div
          className="run-progress"
          role="progressbar"
          aria-label="Agent 运行进度"
          aria-valuemin={0}
          aria-valuemax={data.total}
          aria-valuenow={data.progress}
        >
          <i style={{ width: `${(data.progress / data.total) * 100}%` }} />
        </div>
        <div className="run-node-controls">
          <button
            className="run-node-control"
            type="button"
            onClick={(event) => {
              event.stopPropagation()
              runAction(action)
            }}
          >
            {control}
          </button>
        </div>
      </div>
    </div>
  )
}

function MatrixNodeView({ data }: { data: ContentNode }) {
  const cells = ['能力', '参考', '目标', '整图上下文', '—', '✓', '过程可见', '△', '✓', '结果落位', '△', '✓']
  return (
    <div className="canvas-node node-matrix">
      <NodeHandles />
      <div className="node-content">
        <NodeLabel icon="▦" text="Structured result" />
        <NodeDetails title={data.title} copy={data.copy} />
        <div className="matrix-table">
          {cells.map((cell, index) => (
            <span key={`${cell}-${index}`} className={cell === '✓' ? 'yes' : undefined}>{cell}</span>
          ))}
        </div>
      </div>
    </div>
  )
}

function ResultNodeView({ data }: { data: ResultNode }) {
  const label = data.variant === 'C' ? 'Result 新' : `Result ${data.variant}`
  return (
    <div className={`canvas-node node-result variant-${data.variant}`}>
      <NodeHandles />
      <div className="node-content">
        <NodeLabel icon="◎" text={label} />
        <NodeDetails title={data.title} copy={data.copy} />
        <div className="node-footer">
          <span>可编辑结果</span>
          <span>✓</span>
        </div>
      </div>
    </div>
  )
}

function domainOf(props: NodeProps<CanvasFlowNode>): CanvasNode {
  return props.data.domain
}

function FrameFlowNode(props: NodeProps<CanvasFlowNode>) {
  return <FrameNodeView data={domainOf(props) as FrameNode} />
}
function WebFlowNode(props: NodeProps<CanvasFlowNode>) {
  return <WebNodeView data={domainOf(props) as ContentNode} />
}
function ImageFlowNode(props: NodeProps<CanvasFlowNode>) {
  return <ImageNodeView data={domainOf(props) as ContentNode} />
}
function FileFlowNode(props: NodeProps<CanvasFlowNode>) {
  return <FileNodeView data={domainOf(props) as ContentNode} />
}
function TextFlowNode(props: NodeProps<CanvasFlowNode>) {
  return <TextNodeView data={domainOf(props) as ContentNode} />
}
function GeneratorFlowNode(props: NodeProps<CanvasFlowNode>) {
  return <GeneratorNodeView data={domainOf(props) as GeneratorNode} />
}
function RunFlowNode(props: NodeProps<CanvasFlowNode>) {
  return <RunNodeView data={domainOf(props) as AgentRunNode} />
}
function MatrixFlowNode(props: NodeProps<CanvasFlowNode>) {
  return <MatrixNodeView data={domainOf(props) as ContentNode} />
}
function ResultFlowNode(props: NodeProps<CanvasFlowNode>) {
  return <ResultNodeView data={domainOf(props) as ResultNode} />
}

/** Module-level constant — never recreate per render (React Flow error #002). */
export const canvasNodeTypes = {
  frame: FrameFlowNode,
  web: WebFlowNode,
  image: ImageFlowNode,
  file: FileFlowNode,
  text: TextFlowNode,
  generator: GeneratorFlowNode,
  run: RunFlowNode,
  matrix: MatrixFlowNode,
  result: ResultFlowNode,
}
