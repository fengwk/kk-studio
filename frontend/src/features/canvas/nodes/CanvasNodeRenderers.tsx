/* eslint-disable react-refresh/only-export-components */
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import {
  memo,
  useEffect,
  useState,
} from 'react'
import type { ResourceNode } from '@/features/canvas/domain'
import {
  CanvasResourceMedia,
  CanvasResourceThumbnail,
} from '@/features/canvas/nodes/CanvasResourceMedia'
import { isCompactMediaNode } from '@/features/canvas/resource-node-size'
import type { CanvasFlowNodeData } from '@/features/canvas/types'
import { useI18n } from '@/shared/i18n'

type CanvasFlowNode = Node<CanvasFlowNodeData, 'resource' | 'group'>

function ResourceHandles({ functionNode }: { functionNode: boolean }) {
  const { t } = useI18n()
  return (
    <>
      {functionNode ? (
        <Handle
          type="target"
          position={Position.Left}
          id="in"
          className="canvas-handle target"
          aria-label={t('canvas.node.handleIn')}
        />
      ) : null}
      <Handle
        type="source"
        position={Position.Right}
        id="out"
        className="canvas-handle source"
        aria-label={t('canvas.node.handleOut')}
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
  const { t } = useI18n()
  const [resourceIndex, setResourceIndex] = useState(0)
  const resource = node.resources[resourceIndex] ?? node.resources[0]
  const compactMediaNode = isCompactMediaNode(node)

  useEffect(() => {
    setResourceIndex((current) => Math.min(current, Math.max(0, node.resources.length - 1)))
  }, [node.resources.length])

  const functionLabel = node.function && model
    ? model.outputKind === 'VIDEO'
      ? t('canvas.functionLabel.video', { name: node.name })
      : t('canvas.functionLabel.image', { name: node.name })
    : null

  return (
    <article
      className={[
        'resource-node',
        node.function ? 'function-node' : '',
        compactMediaNode ? 'compact-media-node' : '',
      ].filter(Boolean).join(' ')}
      data-resource-kind={resource?.kind ?? 'EMPTY'}
    >
      <ResourceHandles functionNode={Boolean(node.function)} />
      <div className="resource-node-label">
        {functionLabel ? (
          <span className="resource-node-kind-label" title={functionLabel}>
            {functionLabel}
          </span>
        ) : (
          <span className="resource-node-name" title={node.name}>{node.name}</span>
        )}
      </div>
      <div className="resource-node-content">
        <div
          className="resource-node-preview"
          onDoubleClick={() => {
            if (!node.function && resource?.kind === 'TEXT') {
              callbacks.editTextNode(node)
            }
          }}
        >
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
              <p>{node.function ? t('canvas.node.emptyFunction') : t('canvas.node.empty')}</p>
            </div>
          )}
        </div>
      </div>
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
  const { t } = useI18n()
  const visibleResources = node.resources.slice(0, 4)
  return (
    <div className="resource-index-switcher" aria-label={t('canvas.node.switcherAria')}>
      <button
        type="button"
        className="nodrag"
        aria-label={t('canvas.node.previous')}
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
            className={`nodrag${index === activeIndex ? ' active' : ''}`}
            aria-label={t('canvas.node.viewResource', { index: index + 1 })}
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
        className="nodrag"
        aria-label={t('canvas.node.next')}
        disabled={activeIndex >= node.resources.length - 1}
        onClick={() => onChange(activeIndex + 1)}
      >
        ›
      </button>
    </div>
  )
}

function GroupFlowNode(props: NodeProps<CanvasFlowNode>) {
  if (props.data.kind !== 'group') {
    return null
  }
  return (
    <section className="canvas-group-node">
      <span className="canvas-group-title" title={props.data.group.title}>
        {props.data.group.title}
      </span>
    </section>
  )
}

export const canvasNodeTypes = {
  resource: ResourceFlowNode,
  group: GroupFlowNode,
}
