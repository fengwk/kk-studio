import type { ResourceNode } from '@/features/canvas/domain'
import { groupIdFromFlowId } from '@/features/canvas/projection'
import type {
  CanvasCommandDeleteGroup,
  CanvasCommandDeleteNode,
  CanvasResourceNodeDTO,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
} from '@/shared/api/generated/contracts'

/** 新分组在成员包围盒外侧留出的边距（与世界坐标同单位）。 */
const GROUP_PADDING = 32
/** 分组标题占据的头部高度，成员的包围盒从标题下方开始。 */
const GROUP_HEADER_OFFSET = 52

/** 分组成员及其当前位置（叠加未提交的位置草稿）。 */
export function groupMemberTransforms(
  members: ResourceNode[],
  positionDrafts: Record<string, { x: number; y: number }>,
): CanvasTransformDTO[] {
  return members.map((node) => {
    const draft = positionDrafts[node.id]
    return draft ? { ...node.transform, ...draft } : node.transform
  })
}

/** 由成员包围盒推导分组 transform：左右/下方留出边距，上方让出标题高度。 */
export function groupBounds(memberTransforms: CanvasTransformDTO[]): CanvasTransformDTO {
  const minX = Math.min(...memberTransforms.map((transform) => transform.x)) - GROUP_PADDING
  const minY = Math.min(...memberTransforms.map((transform) => transform.y)) - GROUP_HEADER_OFFSET
  const maxX = Math.max(...memberTransforms.map((transform) => transform.x + transform.width)) + GROUP_PADDING
  const maxY = Math.max(...memberTransforms.map((transform) => transform.y + transform.height)) + GROUP_PADDING
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY }
}

export function deleteNodeCommand(node: CanvasResourceNodeDTO): CanvasCommandDeleteNode {
  return {
    type: 'DELETE_NODE',
    nodeId: node.id,
    expectedResourceIds: node.resources.map((resource) => resource.id),
    expectedFunction: node.function ? { name: node.function.name, args: node.function.args } : null,
  }
}

/** 服务端核对冻结的成员集合后，删除分组并解除成员归属。 */
export function deleteGroupCommand(snapshot: CanvasSnapshotDTO, groupId: string): CanvasCommandDeleteGroup {
  return {
    type: 'DELETE_GROUP',
    groupId,
    expectedMemberNodeIds: snapshot.nodes.filter((node) => node.groupId === groupId).map((node) => node.id),
  }
}

/** 先删除所选分组，再删除所选节点，避免先删成员导致同一批的分组 CAS 失败。 */
export function deleteSelectionCommands(
  snapshot: CanvasSnapshotDTO,
  selectedIds: string[],
): (CanvasCommandDeleteGroup | CanvasCommandDeleteNode)[] {
  const groups: CanvasCommandDeleteGroup[] = []
  const nodes: CanvasCommandDeleteNode[] = []
  for (const id of selectedIds) {
    const groupId = groupIdFromFlowId(id)
    if (groupId && snapshot.groups.some((group) => group.id === groupId)) {
      groups.push(deleteGroupCommand(snapshot, groupId))
    } else {
      const node = snapshot.nodes.find((item) => item.id === id)
      if (node) {
        nodes.push(deleteNodeCommand(node))
      }
    }
  }
  return [...groups, ...nodes]
}
