/**
 * Extract domain position updates from React Flow node change payloads
 * without importing RF Node types into the domain/reducer layer.
 */
export interface PositionChangeLike {
  type: string
  id?: string
  position?: { x: number; y: number } | null
}

export function extractPositionUpdates(
  changes: ReadonlyArray<PositionChangeLike>,
): Array<{ id: string; x: number; y: number }> {
  const updates: Array<{ id: string; x: number; y: number }> = []
  for (const change of changes) {
    if (change.type !== 'position' || !change.position || !change.id) {
      continue
    }
    updates.push({
      id: change.id,
      x: change.position.x,
      y: change.position.y,
    })
  }
  return updates
}
