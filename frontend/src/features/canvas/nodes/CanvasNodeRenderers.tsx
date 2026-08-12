/* eslint-disable react-refresh/only-export-components */
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import { Pencil, X } from 'lucide-react'
import {
  memo,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import type { ResourceNode } from '@/features/canvas/domain'
import { parseFunctionConfig, promptVisibleText } from '@/features/canvas/generation'
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
  const [renaming, setRenaming] = useState(false)
  const [name, setName] = useState(node.name)
  const [resourceIndex, setResourceIndex] = useState(0)
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false)
  const deleteButtonRef = useRef<HTMLButtonElement>(null)
  const confirmDeleteRef = useRef<HTMLButtonElement>(null)
  const resource = node.resources[resourceIndex] ?? node.resources[0]
  const compactMediaNode = isCompactMediaNode(node)

  useEffect(() => {
    setName(node.name)
  }, [node.name])

  useEffect(() => {
    setResourceIndex((current) => Math.min(current, Math.max(0, node.resources.length - 1)))
  }, [node.resources.length])

  useEffect(() => {
    if (deleteConfirmOpen) {
      confirmDeleteRef.current?.focus()
    }
  }, [deleteConfirmOpen])

  const summary = useMemo(() => {
    if (!node.function || !model) {
      return null
    }
    const text = promptVisibleText(parseFunctionConfig(node.function.configJson, model).prompt.segments)
    return text.trim() || null
  }, [model, node.function])

  return (
    <article
      className={[
        'resource-node',
        node.function ? 'function-node' : '',
        compactMediaNode ? 'compact-media-node' : '',
      ].filter(Boolean).join(' ')}
      data-resource-kind={node.resources[0]?.kind ?? model?.outputKind ?? 'EMPTY'}
    >
      <ResourceHandles functionNode={Boolean(node.function)} />
      <header className="resource-node-titlebar">
        <div className="resource-node-title">
          {renaming ? (
            <input
              className="nodrag nowheel"
              value={name}
              aria-label={t('canvas.node.renameAria')}
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
              onPointerDown={(event) => event.stopPropagation()}
            />
          ) : (
            <>
              <strong className="resource-node-name" title={node.name}>{node.name}</strong>
              <button
                type="button"
                className="resource-node-action nodrag"
                aria-label={t('canvas.node.renameAction', { name: node.name })}
                onPointerDown={(event) => event.stopPropagation()}
                onClick={(event) => {
                  event.stopPropagation()
                  setRenaming(true)
                }}
              >
                <Pencil aria-hidden="true" />
              </button>
            </>
          )}
        </div>
        <button
          ref={deleteButtonRef}
          type="button"
          className="resource-node-action resource-node-delete nodrag"
          aria-label={t('canvas.node.deleteAction', { name: node.name })}
          aria-haspopup="dialog"
          aria-expanded={deleteConfirmOpen}
          onPointerDown={(event) => event.stopPropagation()}
          onClick={(event) => {
            event.stopPropagation()
            setDeleteConfirmOpen(true)
          }}
        >
          <X aria-hidden="true" />
        </button>
        {deleteConfirmOpen ? (
          <div
            className="resource-node-delete-confirm nodrag nowheel"
            role="alertdialog"
            aria-label={t('canvas.node.deleteConfirmTitle', { name: node.name })}
            onPointerDown={(event) => event.stopPropagation()}
            onClick={(event) => event.stopPropagation()}
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                event.preventDefault()
                setDeleteConfirmOpen(false)
                window.requestAnimationFrame(() => deleteButtonRef.current?.focus())
              }
            }}
          >
            <strong>{t('canvas.node.deleteConfirmTitle', { name: node.name })}</strong>
            <p>{t('canvas.node.deleteConfirmDescription')}</p>
            <div>
              <button
                type="button"
                onClick={() => {
                  setDeleteConfirmOpen(false)
                  window.requestAnimationFrame(() => deleteButtonRef.current?.focus())
                }}
              >
                {t('canvas.node.deleteCancel')}
              </button>
              <button
                ref={confirmDeleteRef}
                type="button"
                className="danger"
                onClick={() => {
                  setDeleteConfirmOpen(false)
                  callbacks.deleteNode(node.id)
                }}
              >
                {t('canvas.node.deleteConfirm')}
              </button>
            </div>
          </div>
        ) : null}
      </header>
      <div className="resource-node-content">
        <div
          className="resource-node-preview"
          onDoubleClick={() => {
            if (!node.function && node.resources[0]?.kind === 'TEXT') {
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
        {!compactMediaNode && summary ? <p className="resource-node-summary">{summary}</p> : null}
        {!compactMediaNode && node.function ? (
          <FunctionFooter node={node} modelLabel={model?.label ?? node.function.modelKey} />
        ) : !compactMediaNode && resource?.kind === 'TEXT' ? (
          <footer className="resource-node-footer">
            <button
              type="button"
              className="nodrag"
              onClick={() => callbacks.editTextNode(node)}
            >
              {t('canvas.node.editMarkdown')}
            </button>
          </footer>
        ) : null}
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

function FunctionFooter({
  node,
  modelLabel,
}: {
  node: ResourceNode
  modelLabel: string
}) {
  const { t } = useI18n()
  const status = node.run?.status ?? 'READY'
  const stage = node.run?.stage
  return (
    <footer className="function-footer">
      <span>{modelLabel}</span>
      <span className={`run-status ${status.toLowerCase()}`}>
        {status === 'READY'
          ? t('canvas.node.runReady')
          : stage && stage !== status
            ? stage
            : t(functionRunStatusKey(status))}
      </span>
    </footer>
  )
}

function functionRunStatusKey(status: NonNullable<ResourceNode['run']>['status']): string {
  if (status === 'RUNNING') {
    return 'canvas.generation.status.running'
  }
  if (status === 'FAILED') {
    return 'canvas.generation.status.failed'
  }
  if (status === 'CANCELLED') {
    return 'canvas.generation.status.cancelled'
  }
  return 'canvas.generation.status.succeeded'
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

export const canvasNodeTypes = {
  resource: ResourceFlowNode,
  group: GroupFlowNode,
}
