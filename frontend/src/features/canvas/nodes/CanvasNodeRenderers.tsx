/* eslint-disable react-refresh/only-export-components */
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import { memo, useEffect, useState } from 'react'
import type { Resource, ResourceNode } from '@/features/canvas/domain'
import type { CanvasFlowNodeData } from '@/features/canvas/types'

type CanvasFlowNode = Node<CanvasFlowNodeData, 'resource' | 'group'>

function ResourceHandles({ functionNode }: { functionNode: boolean }) {
  return (
    <>
      {functionNode ? (
        <Handle
          type="target"
          position={Position.Left}
          id="in"
          className="canvas-handle target"
          aria-label="Function 引用输入"
        />
      ) : null}
      <Handle
        type="source"
        position={Position.Right}
        id="out"
        className="canvas-handle source"
        aria-label="资源引用输出"
      />
    </>
  )
}

function ResourceFlowNode(props: NodeProps<CanvasFlowNode>) {
  if (props.data.kind !== 'resource') {
    return null
  }
  return <ResourceNodeView data={props.data} />
}

const ResourceNodeView = memo(function ResourceNodeView({
  data,
}: {
  data: Extract<CanvasFlowNodeData, { kind: 'resource' }>
}) {
  const { node, model, callbacks } = data
  const [renaming, setRenaming] = useState(false)
  const [name, setName] = useState(node.name)
  const visibleResources = node.resources.slice(0, 4)

  useEffect(() => {
    setName(node.name)
  }, [node.name])

  return (
    <article
      className={`resource-node ${node.function ? 'function-node' : ''}`}
      data-resource-kind={node.resources[0]?.kind ?? model?.outputKind ?? 'EMPTY'}
      onDoubleClick={() => {
        if (!node.function && node.resources[0]?.kind === 'TEXT') {
          callbacks.editTextNode(node)
        }
      }}
    >
      <ResourceHandles functionNode={Boolean(node.function)} />
      <header className="resource-node-header">
        <span className="resource-kind-icon">
          {kindIcon(node.resources[0]?.kind ?? model?.outputKind)}
        </span>
        {renaming ? (
          <input
            value={name}
            aria-label="节点名称"
            autoFocus
            onChange={(event) => setName(event.target.value)}
            onBlur={() => {
              setRenaming(false)
              callbacks.renameNode(node.id, name)
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.currentTarget.blur()
              } else if (event.key === 'Escape') {
                setName(node.name)
                setRenaming(false)
              }
            }}
          />
        ) : (
          <button type="button" className="resource-node-name" onClick={() => setRenaming(true)}>
            {node.name}
          </button>
        )}
        {node.resources.length > 0 ? (
          <span className="resource-count">{node.resources.length}</span>
        ) : null}
      </header>

      <div className="resource-node-body">
        {visibleResources.length > 0 ? (
          <div className="resource-summary-list">
            {visibleResources.map((resource) => (
              <ResourceSummary key={resource.id} resource={resource} />
            ))}
            {node.resources.length > visibleResources.length ? (
              <div className="resource-summary-more">
                +
                {node.resources.length - visibleResources.length}
                {' 个资源'}
              </div>
            ) : null}
          </div>
        ) : (
          <div className="resource-empty">
            <span>✦</span>
            <p>{node.function ? 'Function 首次成功前暂无资源' : '暂无资源'}</p>
          </div>
        )}
      </div>

      {node.function ? (
        <FunctionFooter node={node} modelLabel={model?.label ?? node.function.modelKey} />
      ) : (
        <footer className="resource-node-footer">
          <span>{node.resources[0]?.mediaType ?? 'Resource'}</span>
          <span>{node.resources[0] ? formatBytes(node.resources[0].size) : ''}</span>
        </footer>
      )}
    </article>
  )
})

function ResourceSummary({ resource }: { resource: Resource }) {
  const detail = resource.kind === 'TEXT'
    ? resource.text?.replace(/\s+/g, ' ').trim() || '空文本'
    : mediaDetail(resource)
  return (
    <div className="resource-summary" data-kind={resource.kind}>
      <span className="resource-summary-icon">{kindIcon(resource.kind)}</span>
      <div>
        <strong>{resource.name}</strong>
        <small>{detail}</small>
      </div>
    </div>
  )
}

function FunctionFooter({
  node,
  modelLabel,
}: {
  node: ResourceNode
  modelLabel: string
}) {
  const status = node.run?.status ?? 'READY'
  return (
    <footer className="function-footer">
      <span>{modelLabel}</span>
      <span className={`run-status ${status.toLowerCase()}`}>
        {node.run?.stage ?? status}
      </span>
    </footer>
  )
}

function GroupFlowNode(props: NodeProps<CanvasFlowNode>) {
  if (props.data.kind !== 'group') {
    return null
  }
  return (
    <section className="canvas-group-node">
      <header>
        <span>□</span>
        {props.data.group.title}
      </header>
    </section>
  )
}

function kindIcon(kind: string | undefined): string {
  if (kind === 'IMAGE') {
    return '▧'
  }
  if (kind === 'VIDEO') {
    return '▶'
  }
  if (kind === 'AUDIO') {
    return '♪'
  }
  if (kind === 'TEXT') {
    return 'T'
  }
  return '✦'
}

function mediaDetail(resource: Resource): string {
  const width = finiteNumber(resource.metadata.width)
  const height = finiteNumber(resource.metadata.height)
  const durationMs = finiteNumber(resource.metadata.durationMs)
  if (width !== null && height !== null) {
    return `${width} × ${height} · ${formatBytes(resource.size)}`
  }
  if (durationMs !== null) {
    return `${formatDuration(durationMs)} · ${formatBytes(resource.size)}`
  }
  return `${resource.mediaType} · ${formatBytes(resource.size)}`
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function formatDuration(durationMs: number): string {
  const seconds = Math.round(durationMs / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function formatBytes(size: string): string {
  try {
    const bytes = BigInt(size)
    const kib = 1024n
    const mib = kib * kib
    if (bytes < kib) {
      return `${bytes} B`
    }
    if (bytes < mib) {
      return `${formatUnit(bytes, kib)} KB`
    }
    return `${formatUnit(bytes, mib)} MB`
  } catch {
    return size
  }
}

function formatUnit(value: bigint, unit: bigint): string {
  const whole = value / unit
  const decimal = (value % unit) * 10n / unit
  return decimal === 0n ? String(whole) : `${whole}.${decimal}`
}

export const canvasNodeTypes = {
  resource: ResourceFlowNode,
  group: GroupFlowNode,
}
