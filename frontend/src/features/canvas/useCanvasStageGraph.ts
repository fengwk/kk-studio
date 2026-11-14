import { useCallback } from 'react'
import type { Connection, Edge, NodeChange, OnSelectionChangeParams } from '@xyflow/react'
import { isValidResourceConnection } from '@/features/canvas/canvas-stage-model'
import type { CanvasSnapshot } from '@/features/canvas/domain'
import type { ResourceNode } from '@/features/canvas/domain'
import { extractPositionUpdates } from '@/features/canvas/node-position-changes'
import type { CanvasFlowNode } from '@/features/canvas/projection'
import type { CanvasPositionUpdate } from '@/features/canvas/types'
import type { CanvasFunctionDefinitionDTO } from '@/shared/api/generated/contracts'

export interface CanvasStageGraphOptions {
  snapshot: CanvasSnapshot | null
  models: CanvasFunctionDefinitionDTO[]
  moveNodes: (updates: CanvasPositionUpdate[]) => void
  commitTransforms: () => void
  createLink: (sourceNodeId: string, targetNodeId: string) => void
  deleteLink: (sourceNodeId: string, targetNodeId: string) => void
  editTextNode: (node: ResourceNode) => void
  setSelection: (nodeIds: string[], selectedLinks?: Array<{ sourceNodeId: string; targetNodeId: string }>) => void
  handleSelectionChangeMenu: (nodeIds: string[]) => void
  closeContextMenu: () => void
}

/**
 * React Flow 的图交互回调：节点位置、连线合法性/建立与删除、选区同步、右键菜单打开与画布点击。
 * 所有回调只做事件到运行时动作的映射，不含渲染状态。
 */
export interface CanvasStageGraphHandlers {
  validConnection: (connection: Connection | Edge) => boolean
  handleNodesChange: (changes: NodeChange[]) => void
  handleSelectionChange: (params: OnSelectionChangeParams) => void
  handleConnect: (connection: Connection) => void
  handleEdgesDelete: (deleted: Edge[]) => void
  handleNodeClick: (event: React.MouseEvent, node: CanvasFlowNode) => void
  handlePaneClick: () => void
  handlePaneContextMenu: (event: React.MouseEvent | MouseEvent) => void
}

export function useCanvasStageGraph({
  snapshot,
  models,
  moveNodes,
  commitTransforms,
  createLink,
  deleteLink,
  editTextNode,
  setSelection,
  handleSelectionChangeMenu,
  closeContextMenu,
}: CanvasStageGraphOptions): CanvasStageGraphHandlers {
  const validConnection = useCallback((connection: Connection | Edge) => (
    isValidResourceConnection(snapshot, connection.source, connection.target, models)
  ), [snapshot, models])

  const handleNodesChange = useCallback((changes: NodeChange[]) => {
    if (!snapshot) {
      return
    }
    const positions = extractPositionUpdates(changes)
    const updates: CanvasPositionUpdate[] = []
    for (const position of positions) {
      if (position.id.startsWith('group:')) {
        const group = snapshot.groups.find((item) => `group:${item.id}` === position.id)
        if (group) {
          updates.push({
            id: position.id,
            kind: 'group',
            transform: {
              ...group.transform,
              x: position.x,
              y: position.y,
            },
          })
        }
        continue
      }
      const node = snapshot.resourceNodes.find((item) => item.id === position.id)
      if (node) {
        updates.push({
          id: position.id,
          kind: 'resource',
          transform: {
            ...node.transform,
            x: position.x,
            y: position.y,
          },
        })
      }
    }
    if (updates.length > 0) {
      moveNodes(updates)
      if (positions.some((position) => !position.dragging)) {
        commitTransforms()
      }
    }
  }, [commitTransforms, moveNodes, snapshot])

  const handleSelectionChange = useCallback((params: OnSelectionChangeParams) => {
    const nodeIds = params.nodes.map((node) => node.id)
    setSelection(
      nodeIds,
      params.edges.map((edge) => ({
        sourceNodeId: edge.source,
        targetNodeId: edge.target,
      })),
    )
    handleSelectionChangeMenu(nodeIds)
  }, [handleSelectionChangeMenu, setSelection])

  const handleConnect = useCallback((connection: Connection) => {
    if (!connection.source || !connection.target || !validConnection(connection)) {
      return
    }
    const source = snapshot?.resourceNodes.find((node) => node.id === connection.source)
    const target = snapshot?.resourceNodes.find((node) => node.id === connection.target)
    if (source && target) {
      createLink(source.id, target.id)
    }
  }, [createLink, snapshot, validConnection])

  const handleEdgesDelete = useCallback((deleted: Edge[]) => {
    for (const edge of deleted) {
      const hasRef = snapshot?.references.some((item) => (
        item.sourceNodeId === edge.source && item.targetNodeId === edge.target
      ))
      if (hasRef) {
        deleteLink(edge.source, edge.target)
      }
    }
  }, [deleteLink, snapshot])

  const handleNodeClick = useCallback((event: React.MouseEvent, node: CanvasFlowNode) => {
    if (event.shiftKey) {
      return
    }
    setSelection([node.id])
    if (
      node.data.kind === 'resource'
      && !node.data.node.function
      && node.data.node.resources[0]?.kind === 'TEXT'
    ) {
      editTextNode(node.data.node)
    }
  }, [editTextNode, setSelection])

  const handlePaneClick = useCallback(() => {
    closeContextMenu()
    setSelection([])
  }, [closeContextMenu, setSelection])

  const handlePaneContextMenu = useCallback((event: React.MouseEvent | MouseEvent) => {
    event.preventDefault()
    closeContextMenu()
  }, [closeContextMenu])

  return {
    validConnection,
    handleNodesChange,
    handleSelectionChange,
    handleConnect,
    handleEdgesDelete,
    handleNodeClick,
    handlePaneClick,
    handlePaneContextMenu,
  }
}
