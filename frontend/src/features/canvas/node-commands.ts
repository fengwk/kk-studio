import type { ResourceNode } from '@/features/canvas/domain'
import type { CanvasSnapshotDTO, CanvasCommandDTO, CanvasTransformDTO } from '@/shared/api/generated/contracts'

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

/** 把节点移出分组并删除分组本身：先逐个解除归属，再按冻结成员集合删除。 */
export function deleteGroupCommands(groupId: string, memberIds: string[]): CanvasCommandDTO[] {
  return [
    ...memberIds.map((nodeId) => ({
      type: 'SET_NODE_GROUP' as const,
      nodeId,
      expectedGroupId: groupId,
      groupId: null,
    })),
    { type: 'DELETE_GROUP', groupId, expectedMemberNodeIds: memberIds },
  ]
}

/** 选区删除：节点携带资源与函数基线，分组先解绑成员再删除。 */
export function deleteSelectionCommands(
  snapshot: CanvasSnapshotDTO,
  selectedIds: string[],
): CanvasCommandDTO[] {
  const commands: CanvasCommandDTO[] = []
  for (const id of selectedIds) {
    const node = snapshot.nodes.find((item) => item.id === id)
    if (node) {
      commands.push({
        type: 'DELETE_NODE',
        nodeId: node.id,
        expectedResourceIds: node.resources.map((resource) => resource.id),
        expectedFunction: node.function ? { name: node.function.name, args: node.function.args } : null,
      })
    }
    const group = snapshot.groups.find((item) => item.id === id)
    if (group) {
      const members = snapshot.nodes.filter((item) => item.groupId === group.id).map((item) => item.id)
      commands.push(...deleteGroupCommands(group.id, members))
    }
  }
  return commands
}
