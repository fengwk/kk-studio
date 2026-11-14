import type { HistoryTreeRow } from '@/features/thread/history-tree'

/**
 * lane gutter 字形：每个 lane 一列，节点列画节点（分叉用 ◆），其余列按连通性画竖线。
 */
export function historyLaneGlyphs(row: HistoryTreeRow): string {
  const width = Math.max(row.gutter.length, row.lane + 1)
  let glyphs = ''
  for (let column = 0; column < width; column += 1) {
    if (column === row.lane) {
      glyphs += row.isFork ? '◆' : '●'
    } else {
      glyphs += row.gutter[column] ? '│' : ' '
    }
  }
  return glyphs
}

/** 默认选中 head，其次保留仍然存在的选择，最后回落到最后一行。 */
export function resolveHistoryRowId(
  rows: HistoryTreeRow[],
  currentId: string | null,
  headEntryId: string | null,
): string | null {
  if (rows.length === 0) {
    return null
  }
  if (currentId != null && rows.some((row) => row.entry.entryId === currentId)) {
    return currentId
  }
  if (headEntryId != null && rows.some((row) => row.entry.entryId === headEntryId)) {
    return headEntryId
  }
  return rows[rows.length - 1]!.entry.entryId
}
