/* eslint-disable react-refresh/only-export-components */
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import {
  AudioLines,
  FileText,
  Group,
  Image,
  Images,
  PanelsTopLeft,
  Sparkles,
  Video,
} from 'lucide-react'
import { memo, type ReactNode } from 'react'
import type { ResourceNode } from '@/features/canvas/domain'
import { CanvasNodeContainer } from '@/features/canvas/nodes/CanvasNodeContainer'
import { CanvasResourceGrid } from '@/features/canvas/nodes/CanvasResourceGrid'
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
  const descriptor = nodeDescriptor(node, model?.outputKind ?? null, t)

  return (
    <CanvasNodeContainer
      className="resource-node"
      icon={descriptor.icon}
      typeLabel={descriptor.label}
      name={node.name}
      accessories={<ResourceHandles functionNode={Boolean(node.function)} />}
      bodyClassName="resource-node-body"
      onBodyDoubleClick={() => {
        if (!node.function && node.resources[0]?.kind === 'TEXT') {
          callbacks.editTextNode(node)
        }
      }}
    >
      <CanvasResourceGrid node={node} />
    </CanvasNodeContainer>
  )
})

function GroupFlowNode(props: NodeProps<CanvasFlowNode>) {
  const { t } = useI18n()
  if (props.data.kind !== 'group') {
    return null
  }
  return (
    <CanvasNodeContainer
      as="section"
      className="canvas-group-node"
      icon={<PanelsTopLeft />}
      typeLabel={t('canvas.node.type.group')}
      name={props.data.group.title}
      bodyClassName="canvas-group-body"
    >
      <span className="sr-only">{props.data.group.title}</span>
    </CanvasNodeContainer>
  )
}

function nodeDescriptor(
  node: ResourceNode,
  functionOutputKind: 'IMAGE' | 'VIDEO' | null,
  t: ReturnType<typeof useI18n>['t'],
): { icon: ReactNode; label: string } {
  if (node.function) {
    if (functionOutputKind === 'IMAGE') {
      return { icon: <Sparkles />, label: t('canvas.node.type.imageFunction') }
    }
    if (functionOutputKind === 'VIDEO') {
      return { icon: <Sparkles />, label: t('canvas.node.type.videoFunction') }
    }
    return { icon: <Sparkles />, label: t('canvas.node.type.function') }
  }
  const kinds = new Set(node.resources.map((resource) => resource.kind))
  if (kinds.size !== 1) {
    return { icon: <Images />, label: t('canvas.node.type.resource') }
  }
  const kind = node.resources[0]?.kind
  if (kind === 'IMAGE') {
    return { icon: <Image />, label: t('canvas.node.type.image') }
  }
  if (kind === 'VIDEO') {
    return { icon: <Video />, label: t('canvas.node.type.video') }
  }
  if (kind === 'AUDIO') {
    return { icon: <AudioLines />, label: t('canvas.node.type.audio') }
  }
  if (kind === 'TEXT') {
    return { icon: <FileText />, label: t('canvas.node.type.text') }
  }
  return { icon: <Group />, label: t('canvas.node.type.resource') }
}

export const canvasNodeTypes = {
  resource: ResourceFlowNode,
  group: GroupFlowNode,
}
