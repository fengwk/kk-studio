import type { CanvasSnapshot, Group, ResourceNode } from '@/features/canvas/domain'
import { groupIdFromFlowId } from '@/features/canvas/projection'
import type { CanvasFunctionModelDTO } from '@/shared/api/contracts/studio'

export type ContextMenuTarget =
  | { kind: 'resource'; node: ResourceNode; model: CanvasFunctionModelDTO | null }
  | { kind: 'group'; group: Group }
  | { kind: 'multi'; nodeIds: string[]; hasUngroupedResource: boolean }

/**
 * 根据快照与节点 id 列表构建右键菜单目标：单节点解码 group flow id 后指向
 * Group/Resource（Resource 携带匹配的 function model），多选只保留
 * 「全部未分组」标志。任何 id 无法解析时返回 null（右键不打开菜单）。
 */
export function buildContextMenuTarget(
  snapshot: CanvasSnapshot,
  nodeIds: string[],
  models: readonly CanvasFunctionModelDTO[],
): ContextMenuTarget | null {
  if (nodeIds.length === 1) {
    const id = nodeIds[0] as string
    const groupId = groupIdFromFlowId(id)
    if (groupId) {
      const group = snapshot.groups.find((item) => item.id === groupId)
      return group ? { kind: 'group', group } : null
    }
    const node = snapshot.resourceNodes.find((item) => item.id === id)
    if (!node) {
      return null
    }
    return {
      kind: 'resource',
      node,
      model: node.function
        ? models.find((model) => model.key === node.function?.modelKey) ?? null
        : null,
    }
  }
  const selectedResources = nodeIds
    .map((id) => snapshot.resourceNodes.find((item) => item.id === id))
    .filter((node) => Boolean(node))
  const hasUngroupedResource = (
    selectedResources.length === nodeIds.length
    && selectedResources.every((node) => !node?.groupId)
  )
  return { kind: 'multi', nodeIds, hasUngroupedResource }
}

/**
 * React Flow 连接合法性：必须有明确且不同的 source/target；source 节点
 * 存在且至少携带一个资源，target 节点存在且是 Function 节点。
 */
export function isValidResourceConnection(
  snapshot: CanvasSnapshot | null,
  source: string | null | undefined,
  target: string | null | undefined,
): boolean {
  if (!snapshot || !source || !target || source === target) {
    return false
  }
  const sourceNode = snapshot.resourceNodes.find((node) => node.id === source)
  const targetNode = snapshot.resourceNodes.find((node) => node.id === target)
  return Boolean(sourceNode && sourceNode.resources.length > 0 && targetNode?.function)
}

/** 右键菜单 overlay 的稳定 key：按 target 类型编码，避免同目标重复挂载。 */
export function contextMenuTargetKey(target: ContextMenuTarget): string {
  if (target.kind === 'resource') {
    return `resource:${target.node.id}`
  }
  if (target.kind === 'group') {
    return `group:${target.group.id}`
  }
  return `multi:${target.nodeIds.join(',')}`
}

/** ID 列表集合相等（顺序无关）；用于判断选区是否实质变化。 */
export function sameIdList(left: readonly string[], right: readonly string[]): boolean {
  if (left.length !== right.length) {
    return false
  }
  const rightIds = new Set(right)
  return left.every((id) => rightIds.has(id))
}
