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
import { useI18n } from '@/shared/i18n'

type CanvasFlowNode = Node<CanvasFlowNodeData, CanvasNode['type']>

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
  const { t } = useI18n()
  return (
    <div className="canvas-node node-web">
      <NodeHandles />
      <div className="web-strip" />
      <div className="node-content">
        <NodeLabel icon="⌁" text={t('canvas.node.webReference')} />
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
  const { t } = useI18n()
  return (
    <div className="canvas-node node-image">
      <NodeHandles />
      <div className="node-content">
        <div className="image-preview" />
        <NodeLabel icon="◒" text={t('canvas.node.imageReference')} />
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
  const { t } = useI18n()
  return (
    <div className="canvas-node node-file">
      <NodeHandles />
      <div className="node-content">
        <span className="file-thumb">PDF</span>
        <NodeLabel icon="▤" text={t('canvas.node.file')} />
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
  const { t } = useI18n()
  return (
    <div className="canvas-node node-text">
      <NodeHandles />
      <div className="node-content">
        <NodeLabel icon="T" text={data.meta || t('canvas.node.text')} />
        <NodeDetails title={data.title} copy={data.copy} />
      </div>
    </div>
  )
}

function GeneratorPreview({ node, statusLabel }: { node: GeneratorNode; statusLabel: string }) {
  const draftBadge = node.status === 'draft'
    ? <span className="generator-preview-status">{statusLabel}</span>
    : null
  if (node.generationMode === 'text') {
    return (
      <div className={`generator-preview generator-preview-text ${node.status}`}>
        {draftBadge}
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
        {draftBadge}
        <i />
        <i />
        <span>✦</span>
      </div>
    )
  }
  return (
    <div className={`generator-preview generator-preview-video ${node.status}`}>
      {draftBadge}
      <i />
      <span className="generator-play">▶</span>
      <small>00:06</small>
    </div>
  )
}

function GeneratorNodeView({ data }: { data: GeneratorNode }) {
  const { activateGenerator } = useCanvasRuntime()
  const { t } = useI18n()
  const profile = GENERATION_PROFILES[data.generationMode]
  const profileLabel = t(profile.labelKey)
  const status = t(
    data.status === 'generated'
      ? 'canvas.generation.status.generated'
      : 'canvas.generation.status.draft',
  )
  return (
    <div
      className={`canvas-node node-generator ${data.generationMode}`}
      tabIndex={0}
      role="button"
      data-generation-mode={data.generationMode}
      data-generation-status={data.status}
      aria-label={t('canvas.node.openWorkbench', { title: data.title })}
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
        <GeneratorPreview node={data} statusLabel={status} />
        <div className="generator-node-head">
          <NodeLabel icon={profile.icon} text={profileLabel} />
          <span className={`generator-state ${data.status}`}>{status}</span>
        </div>
        <NodeDetails title={data.title} copy={data.copy} />
        <div className="node-footer">
          <span>{data.status === 'generated' ? data.meta : t(data.capability)}</span>
          <span>{data.status === 'generated' ? '✓' : '↗'}</span>
        </div>
      </div>
    </div>
  )
}

function RunNodeView({ data }: { data: AgentRunNode }) {
  const { runAction } = useCanvasRuntime()
  const { t } = useI18n()
  const running = data.status === 'running'
  const paused = data.status === 'paused'
  const status = t(
    running
      ? 'canvas.agent.run.status.running'
      : paused
        ? 'canvas.agent.run.status.paused'
        : 'canvas.agent.run.status.completed',
  )
  const statusText = t('canvas.agent.run.status.withProgress', {
    status,
    progress: data.progress,
    total: data.total,
  })
  const control = t(
    running
      ? 'canvas.agent.run.control.pause'
      : paused
        ? 'canvas.agent.run.control.resume'
        : 'canvas.agent.run.control.retry',
  )
  const action = running ? 'pause' : paused ? 'resume' : 'retry'
  return (
    <div className="canvas-node node-run">
      <NodeHandles />
      <div className="node-content">
        <div className="run-header">
          <NodeLabel icon="✦" text={t('canvas.agent.run.label')} />
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
                {t(step)}
              </div>
            )
          })}
        </div>
        <div
          className="run-progress"
          role="progressbar"
          aria-label={t('canvas.agent.run.progress')}
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
  const { t } = useI18n()
  const cells = [
    'canvas.matrix.capability',
    'canvas.matrix.reference',
    'canvas.matrix.target',
    'canvas.matrix.wholeCanvas',
    '—',
    '✓',
    'canvas.matrix.visibleProcess',
    '△',
    '✓',
    'canvas.matrix.resultPlacement',
    '△',
    '✓',
  ]
  return (
    <div className="canvas-node node-matrix">
      <NodeHandles />
      <div className="node-content">
        <NodeLabel icon="▦" text={t('canvas.node.structuredResult')} />
        <NodeDetails title={data.title} copy={data.copy} />
        <div className="matrix-table">
          {cells.map((cell, index) => (
            <span key={`${cell}-${index}`} className={cell === '✓' ? 'yes' : undefined}>
              {cell.startsWith('canvas.') ? t(cell) : cell}
            </span>
          ))}
        </div>
      </div>
    </div>
  )
}

function ResultNodeView({ data }: { data: ResultNode }) {
  const { t } = useI18n()
  const label = data.variant === 'C' ? t('canvas.node.resultNew') : `${t('canvas.node.result')} ${data.variant}`
  return (
    <div className={`canvas-node node-result variant-${data.variant}`}>
      <NodeHandles />
      <div className="node-content">
        <NodeLabel icon="◎" text={label} />
        <NodeDetails title={data.title} copy={data.copy} />
        <div className="node-footer">
          <span>{t('canvas.node.editableResult')}</span>
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
