import type {
  CanvasGroupDTO,
  CanvasPatchDTO,
  CanvasReferenceDTO,
  CanvasResourceNodeDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'
import { compareCanvasRevisions } from '@/shared/lib/canvas-version'
import { deriveReferencesFromNodes } from '@/features/canvas/domain'

/**
 * 应用单个 graph patch。返回 null 表示该 patch 无法应用（重复或过期的 revision）。
 */
export function applyEntityPatch(
  snapshot: CanvasSnapshotDTO,
  patch: CanvasPatchDTO,
): CanvasSnapshotDTO | null {
  if (compareCanvasRevisions(patch.revision, snapshot.document.revision) <= 0) {
    return null
  }
  const nextNodes = applyNodePatches(snapshot.nodes, patch.nodes)
  const nextGroups = applyGroupPatches(snapshot.groups, patch.groups)
  const nextReferences = deriveReferencesFromNodes(nextNodes) as unknown as CanvasReferenceDTO[]

  return {
    document: {
      ...snapshot.document,
      revision: patch.revision,
    },
    nodes: nextNodes,
    groups: nextGroups,
    references: nextReferences,
  }
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
