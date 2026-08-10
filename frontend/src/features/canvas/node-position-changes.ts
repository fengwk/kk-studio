/**
 * 从 React Flow node change payload 中提取领域位置更新，
 * 避免在领域/reducer 层引入 RF Node 类型。
 */
export interface PositionChangeLike {
  type: string
  id?: string
  position?: { x: number; y: number } | null
  dragging?: boolean
}

export function extractPositionUpdates(
  changes: ReadonlyArray<PositionChangeLike>,
): Array<{ id: string; x: number; y: number; dragging: boolean }> {
  const updates: Array<{ id: string; x: number; y: number; dragging: boolean }> = []
  for (const change of changes) {
    if (change.type !== 'position' || !change.position || !change.id) {
      continue
    }
    updates.push({
      id: change.id,
      x: change.position.x,
      y: change.position.y,
      dragging: Boolean(change.dragging),
    })
  }
  return updates
}
