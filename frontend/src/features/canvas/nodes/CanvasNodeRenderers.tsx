/* eslint-disable react-refresh/only-export-components */
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import { memo, useEffect, useState } from 'react'
import type { ResourceNode } from '@/features/canvas/domain'
import {
  CanvasResourceMedia,
  CanvasResourceThumbnail,
} from '@/features/canvas/nodes/CanvasResourceMedia'
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
  const [resourceIndex, setResourceIndex] = useState(0)
  const resource = node.resources[resourceIndex] ?? node.resources[0]

  useEffect(() => {
    setName(node.name)
  }, [node.name])

  useEffect(() => {
    setResourceIndex((current) => Math.min(current, Math.max(0, node.resources.length - 1)))
  }, [node.resources.length])

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
        {resource ? (
          <div className="resource-viewer">
            <CanvasResourceMedia resource={resource} />
            {node.resources.length > 1 ? (
              <ResourceIndexSwitcher
                node={node}
                activeIndex={resourceIndex}
                onChange={setResourceIndex}
              />
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
          {resource?.kind === 'TEXT' ? (
            <button type="button" onClick={() => callbacks.editTextNode(node)}>
              编辑 Markdown
            </button>
          ) : (
            <span>{resource?.mediaType ?? 'Resource'}</span>
          )}
          <span>{resource ? formatBytes(resource.size) : ''}</span>
        </footer>
      )}
    </article>
  )
})

function ResourceIndexSwitcher({
  node,
  activeIndex,
  onChange,
}: {
  node: ResourceNode
  activeIndex: number
  onChange: (index: number) => void
}) {
  const visibleResources = node.resources.slice(0, 4)
  return (
    <div className="resource-index-switcher" aria-label="资源索引切换">
      <button
        type="button"
        aria-label="上一个资源"
        disabled={activeIndex === 0}
        onClick={() => onChange(activeIndex - 1)}
      >
        ‹
      </button>
      <div className="resource-index-thumbnails">
        {visibleResources.map((resource, index) => (
          <button
            key={resource.id}
            type="button"
            className={index === activeIndex ? 'active' : ''}
            aria-label={`查看资源 ${index + 1}`}
            aria-pressed={index === activeIndex}
            onClick={() => onChange(index)}
          >
            <CanvasResourceThumbnail resource={resource} />
          </button>
        ))}
        {node.resources.length > visibleResources.length ? (
          <span className="resource-index-more">
            +
            {node.resources.length - visibleResources.length}
          </span>
        ) : null}
      </div>
      <span className="resource-index-count">
        {activeIndex + 1}
        {' / '}
        {node.resources.length}
      </span>
      <button
        type="button"
        aria-label="下一个资源"
        disabled={activeIndex >= node.resources.length - 1}
        onClick={() => onChange(activeIndex + 1)}
      >
        ›
      </button>
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
