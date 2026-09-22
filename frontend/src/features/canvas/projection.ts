import type { Edge, Node } from '@xyflow/react'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import type { CanvasSnapshot, Link } from '@/features/canvas/domain'
import type {
  CanvasFlowNodeData,
  CanvasLinkSelection,
  CanvasNodeCallbacks,
} from '@/features/canvas/types'
import type {
  CanvasFunctionModelDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

export type CanvasFlowNode = Node<CanvasFlowNodeData, 'resource' | 'group'>
export type CanvasFlowEdge = Edge

export function projectNodes(
  snapshot: CanvasSnapshot,
  selectedIds: string[],
  models: CanvasFunctionModelDTO[],
  callbacks: CanvasNodeCallbacks,
  positionDrafts: Readonly<Record<string, { x: number; y: number }>> = {},
): CanvasFlowNode[] {
  const selected = new Set(selectedIds)
  const modelByKey = new Map(models.map((model) => [model.key, model]))
  const groupDrafts = new Map(snapshot.groups.map((group) => {
    const id = groupFlowId(group.id)
    const position = positionDrafts[id]
    return [group.id, position ? {
      x: position.x,
      y: position.y,
      deltaX: position.x - group.transform.x,
      deltaY: position.y - group.transform.y,
    } : null] as const
  }))
  const groups: CanvasFlowNode[] = snapshot.groups.map((group) => {
    const draft = groupDrafts.get(group.id)
    return {
      id: groupFlowId(group.id),
      type: 'group',
      position: draft
        ? { x: draft.x, y: draft.y }
        : { x: group.transform.x, y: group.transform.y },
      selected: selected.has(groupFlowId(group.id)),
      style: { width: group.transform.width, height: group.transform.height },
      measured: { width: group.transform.width, height: group.transform.height },
      data: { kind: 'group', group },
      zIndex: -1,
    }
  })
  const nodes: CanvasFlowNode[] = snapshot.resourceNodes.map((node) => {
    const ownDraft = positionDrafts[node.id]
    const groupDraft = node.groupId ? groupDrafts.get(node.groupId) : null
    const position = ownDraft
      ?? (groupDraft
        ? {
          x: node.transform.x + groupDraft.deltaX,
          y: node.transform.y + groupDraft.deltaY,
        }
        : { x: node.transform.x, y: node.transform.y })
    return {
      id: node.id,
      type: 'resource',
      position,
      selected: selected.has(node.id),
      style: { width: node.transform.width, height: node.transform.height },
      measured: { width: node.transform.width, height: node.transform.height },
      data: {
        kind: 'resource',
        node,
        model: node.function ? modelByKey.get(node.function.modelKey) ?? null : null,
        callbacks,
      },
      zIndex: 1,
    }
  })
  return [...groups, ...nodes]
}

export function projectEdges(
  links: Link[],
  selectedLinks: CanvasLinkSelection[] = [],
): CanvasFlowEdge[] {
  const selected = new Set(selectedLinks.map((link) => (
    `${link.sourceNodeId}->${link.targetNodeId}`
  )))
  return links.map((link) => ({
    id: `${link.sourceNodeId}->${link.targetNodeId}`,
    source: link.sourceNodeId,
    target: link.targetNodeId,
    sourceHandle: 'out',
    targetHandle: 'in',
    type: 'default',
    focusable: true,
    selectable: true,
    selected: selected.has(`${link.sourceNodeId}->${link.targetNodeId}`),
    interactionWidth: 18,
  }))
}

export function groupFlowId(groupId: string): string {
  return `group:${groupId}`
}

/**
 * 从 flow id 解码 group UUID。UUID 不含 ':'，前缀切片是安全的；
 * 非 canonical UUID 后缀一律拒绝。
 */
export function groupIdFromFlowId(flowId: string): UUIDString | null {
  const groupId = flowId.startsWith('group:') ? flowId.slice('group:'.length) : ''
  return isCanonicalUuid(groupId) ? groupId as UUIDString : null
}
