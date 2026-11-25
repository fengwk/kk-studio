import type { WorkspaceFolderDTO, WorkspaceThreadDTO, WorkspaceTreeDTO, WorkspaceThreadState } from '@/shared/api/generated/contracts'

export type TreeRow = {
  id: string
  name: string
  parentId: string | null
  level: number
  children: TreeRow[]
} & ({ kind: 'folder' | 'session' | 'canvas' } | { kind: 'thread'; thread: WorkspaceThreadDTO })

export function treeRows(tree: WorkspaceTreeDTO): TreeRow[] {
  function folderRows(folder: WorkspaceFolderDTO, parentId: string | null, level: number): TreeRow {
    return { id: folder.id, name: folder.name, kind: 'folder', parentId, level, children: [
      ...folder.resources.map((resource): TreeRow => ({
        id: resource.id, name: resource.title, kind: resource.kind === 'SESSION' ? 'session' : 'canvas',
        parentId: folder.id, level: level + 1,
        children: (resource.threads ?? []).map(thread => ({
          id: thread.id, name: thread.title, kind: 'thread', thread,
          parentId: resource.id, level: level + 2, children: [],
        })),
      })),
      ...folder.children.map(child => folderRows(child, folder.id, level + 1)),
    ] }
  }
  return tree.folders.map(folder => folderRows(folder, null, 1))
}

export function indexRows(rows: TreeRow[]): Map<string, TreeRow> {
  const index = new Map<string, TreeRow>()
  function visit(row: TreeRow) {
    index.set(row.id, row)
    row.children.forEach(visit)
  }
  rows.forEach(visit)
  return index
}

export function visibleRows(rows: TreeRow[], expanded: ReadonlySet<string>, filter: string): TreeRow[] {
  const search = filter.trim().toLowerCase()
  function visit(row: TreeRow): TreeRow[] {
    if (!search) return [row, ...(expanded.has(row.id) ? row.children.flatMap(visit) : [])]
    const descendants = row.children.flatMap(visit)
    return descendants.length || row.name.toLowerCase().includes(search) ? [row, ...descendants] : []
  }
  return rows.flatMap(visit)
}

export const threadStates: readonly WorkspaceThreadState[] = ['NEEDS_YOU', 'RUNNING', 'WAITING', 'FAILED', 'IDLE']

export function groupThreads(rows: Iterable<TreeRow>) {
  const groups = new Map(threadStates.map(state => [state, [] as Extract<TreeRow, { kind: 'thread' }>[]]))
  for (const row of rows) if (row.kind === 'thread') groups.get(row.thread.state)?.push(row)
  for (const group of groups.values()) group.sort((a, b) => b.thread.lastActivityAt - a.thread.lastActivityAt)
  return groups
}

export type TreeDrop = { parentId: string | null; beforeId: string | null; position: 'before' | 'after' | 'into' }

/** Resolve the visible drop marker and the next sibling in server order, not display order. */
export function resolveTreeDrop(roots: TreeRow[], index: ReadonlyMap<string, TreeRow>, sourceId: string, targetId: string,
  relativeY: number): TreeDrop | undefined {
  const source = index.get(sourceId), target = index.get(targetId)
  if (!source || !target || source === target || source.kind === 'thread' || target.kind === 'thread') return
  const folder = source.kind === 'folder'
  if (folder && target.kind !== 'folder') return
  const into = target.kind === 'folder' && (!folder || relativeY >= 0.25 && relativeY <= 0.75)
  const parentId = into ? target.id : target.parentId
  for (let id = parentId; id; id = index.get(id)?.parentId ?? null) if (id === source.id) return
  if (folder && source.parentId === null && parentId !== null && roots.length === 1) return
  const siblings = (parentId ? index.get(parentId)?.children ?? [] : roots)
    .filter(row => (row.kind === 'folder') === folder && row.id !== sourceId)
  const position = into ? 'into' : relativeY < 0.5 ? 'before' : 'after'
  const beforeId = into ? (!folder && source.parentId === parentId ? siblings[0]?.id ?? null : null)
    : position === 'before' ? target.id : siblings[siblings.indexOf(target) + 1]?.id ?? null
  return { parentId, beforeId, position }
}
