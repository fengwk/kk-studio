import { useEffect, useRef, useState } from 'react'
import { resolveHistoryRowId } from '@/features/thread/history-tree-row-state'
import type { HistoryTreeRow } from '@/features/thread/history-tree'

export interface HistoryTreeSelection {
  selectedId: string | null
  setSelectedId: (entryId: string | null) => void
  selectedRef: React.RefObject<HTMLButtonElement | null>
  selectedRow: HistoryTreeRow | null
  selectedIndex: number
  moveSelection: (delta: number) => void
}

/**
 * 历史树的查看焦点：只表达“当前看哪一行”，不触动 durable control。
 *
 * 行集合变化时优先保留仍然存在的选中行，其次回落到 head，最后回落到最后一行
 * （即最新一行）；选中变化后把该行滚入可见范围。
 */
export function useHistoryTreeSelection(
  rows: HistoryTreeRow[],
  headEntryId: string | null,
): HistoryTreeSelection {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selectedRef = useRef<HTMLButtonElement | null>(null)
  const selectedRow = rows.find((row) => row.entry.entryId === selectedId) ?? null
  const selectedIndex = selectedRow == null ? -1 : rows.indexOf(selectedRow)

  useEffect(() => {
    setSelectedId((current) => resolveHistoryRowId(rows, current, headEntryId))
  }, [rows, headEntryId])
  useEffect(() => {
    selectedRef.current?.scrollIntoView?.({ block: 'nearest' })
  }, [selectedId])

  function moveSelection(delta: number) {
    if (rows.length === 0) {
      return
    }
    const index = Math.max(0, selectedIndex)
    setSelectedId(rows[(index + delta + rows.length) % rows.length]?.entry.entryId ?? null)
  }

  return { selectedId, setSelectedId, selectedRef, selectedRow, selectedIndex, moveSelection }
}
