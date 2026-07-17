import type { Edge, Node } from '@xyflow/react'
import type { CanvasLink, CanvasNode } from '@/features/canvas/types'

export type CanvasFlowNodeData = {
  domain: CanvasNode
} & Record<string, unknown>

export type CanvasFlowNode = Node<CanvasFlowNodeData, CanvasNode['type']>
export type CanvasFlowEdge = Edge

/** Project domain nodes into React Flow view models without leaking RF types into persistence. */
export function projectNodes(nodes: CanvasNode[], selectedIds: string[]): CanvasFlowNode[] {
  const selected = new Set(selectedIds)
  return nodes.map((node) => ({
    id: node.id,
    type: node.type,
    position: { x: node.x, y: node.y },
    selected: selected.has(node.id),
    style: { width: node.width, height: node.height },
    measured: { width: node.width, height: node.height },
    data: { domain: node },
    zIndex: node.type === 'frame' ? 0 : 1,
  }))
}

export function projectEdges(links: CanvasLink[]): CanvasFlowEdge[] {
  return links.map((link) => ({
    id: link.id,
    source: link.source,
    target: link.target,
    sourceHandle: 'out',
    targetHandle: 'in',
    type: 'default',
    focusable: false,
    selectable: false,
    interactionWidth: 8,
  }))
}
