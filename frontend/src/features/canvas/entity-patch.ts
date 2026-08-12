import type {
  CanvasChangesDTO,
  CanvasGroupDTO,
  CanvasLinkDTO,
  CanvasPatchDTO,
  CanvasResourceNodeDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'
import { compareCanvasVersions } from '@/shared/lib/canvas-version'

/**
 * 应用单个 graph patch。返回 null 表示该 patch 无法连续应用：
 * - `patch.version <= snapshot.document.version`：重复/过期 patch，调用方应忽略；
 * - `patch.baseVersion !== snapshot.document.version`：存在 gap，
 *   调用方必须通过 changes（getCanvasChanges）或全量快照恢复。
 * 版本是 canonical 非负十进制字符串，比较使用长度/字典序（bigint-safe）。
 */
export function applyEntityPatch(
  snapshot: CanvasSnapshotDTO,
  patch: CanvasPatchDTO,
): CanvasSnapshotDTO | null {
  if (compareCanvasVersions(patch.version, snapshot.document.version) <= 0) {
    return null
  }
  if (patch.baseVersion !== snapshot.document.version) {
    return null
  }
  return {
    document: { ...snapshot.document, version: patch.version },
    nodes: applyNodePatches(snapshot.nodes, patch.nodes),
    groups: applyGroupPatches(snapshot.groups, patch.groups),
    links: applyLinkPatches(snapshot.links, patch.links),
  }
}

/**
 * 应用 changes 恢复载荷：优先整体替换 snapshot；否则按顺序折叠连续
 * patches。任一 patch 无法连续应用（或载荷为空但版本落后）时返回 null，
 * 调用方必须回退到全量快照。
 */
export function applyCanvasChanges(
  snapshot: CanvasSnapshotDTO,
  changes: CanvasChangesDTO,
): CanvasSnapshotDTO | null {
  if (changes.snapshot) {
    return compareCanvasVersions(changes.snapshot.document.version, snapshot.document.version) >= 0
      ? changes.snapshot
      : null
  }
  let current = snapshot
  for (const patch of changes.patches) {
    const next = applyEntityPatch(current, patch)
    if (!next) {
      return null
    }
    current = next
  }
  return current
}

function applyNodePatches(
  nodes: CanvasResourceNodeDTO[],
  patches: CanvasPatchDTO['nodes'],
): CanvasResourceNodeDTO[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  for (const patch of patches) {
    if (patch.op === 'UPSERT') {
      byId.set(patch.node.id, patch.node)
    } else {
      byId.delete(patch.nodeId)
    }
  }
  return [...byId.values()]
}

function applyGroupPatches(
  groups: CanvasGroupDTO[],
  patches: CanvasPatchDTO['groups'],
): CanvasGroupDTO[] {
  const byId = new Map(groups.map((group) => [group.id, group]))
  for (const patch of patches) {
    if (patch.op === 'UPSERT') {
      byId.set(patch.group.id, patch.group)
    } else {
      byId.delete(patch.groupId)
    }
  }
  return [...byId.values()]
}

function applyLinkPatches(
  links: CanvasLinkDTO[],
  patches: CanvasPatchDTO['links'],
): CanvasLinkDTO[] {
  const linkKey = (link: Pick<CanvasLinkDTO, 'sourceNodeId' | 'targetNodeId'>) =>
    `${link.sourceNodeId}->${link.targetNodeId}`
  const byKey = new Map(links.map((link) => [linkKey(link), link]))
  for (const patch of patches) {
    if (patch.op === 'UPSERT') {
      byKey.set(linkKey(patch.link), patch.link)
    } else {
      byKey.delete(`${patch.sourceNodeId}->${patch.targetNodeId}`)
    }
  }
  return [...byKey.values()]
}
