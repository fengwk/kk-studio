import { useCallback, useRef } from 'react'
import { currentCanvasSnapshot, type CanvasScope } from '@/features/canvas/canvas-controller-scope'
import { normalizeNodeAlias, uniqueNodeAlias } from '@/features/canvas/node-alias'
import { CANVAS_SINGLE_RESOURCE_NODE_SIZE } from '@/features/canvas/resource-node-size'
import type { CanvasSnapshotDTO, CanvasTransformDTO } from '@/shared/api/generated/contracts'

const NODE_SIZE: CanvasTransformDTO = {
  x: 120,
  y: 120,
  ...CANVAS_SINGLE_RESOURCE_NODE_SIZE,
}

/**
 * 新节点的命名与落位：别名在当前快照名称与本地已预留名称中去重（预留覆盖命令在途期间），新节点按现有数量排成 4 列网格。
 */
export function useCanvasNodePlacement(scope: CanvasScope, snapshot: CanvasSnapshotDTO | undefined) {
  const reservedNodeAliasesRef = useRef(new Set<string>())

  const suggestNodeAlias = useCallback((preferred: string) => {
    const names = [
      ...(currentCanvasSnapshot(scope, snapshot)?.nodes.map((node) => node.name) ?? []),
      ...reservedNodeAliasesRef.current,
    ]
    return uniqueNodeAlias(preferred, names)
  }, [scope, snapshot])

  const reserveNodeAlias = useCallback((preferred: string) => {
    const alias = suggestNodeAlias(preferred)
    reservedNodeAliasesRef.current.add(alias)
    return alias
  }, [suggestNodeAlias])

  const releaseNodeAlias = useCallback((alias: string) => {
    const normalized = normalizeNodeAlias(alias)
    for (const reserved of reservedNodeAliasesRef.current) {
      if (normalizeNodeAlias(reserved) === normalized) {
        reservedNodeAliasesRef.current.delete(reserved)
        return
      }
    }
  }, [])

  const clearReservedAliases = useCallback(() => {
    reservedNodeAliasesRef.current.clear()
  }, [])

  const nextTransform = useCallback((
    size: Pick<CanvasTransformDTO, 'width' | 'height'> = NODE_SIZE,
  ): CanvasTransformDTO => {
    const count = currentCanvasSnapshot(scope, snapshot)?.nodes.length ?? 0
    return {
      ...NODE_SIZE,
      ...size,
      x: 100 + (count % 4) * 360,
      y: 100 + Math.floor(count / 4) * 380,
    }
  }, [scope, snapshot])

  return { suggestNodeAlias, reserveNodeAlias, releaseNodeAlias, clearReservedAliases, nextTransform }
}
