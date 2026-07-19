import { useState } from 'react'
import {
  buildSessionEntryTree,
  type SessionTreeFilter,
} from '@/features/ai/session-entry-tree'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'

const FILTERS: Array<{ value: SessionTreeFilter; label: string }> = [
  { value: 'default', label: '默认' },
  { value: 'no-tools', label: '无工具' },
  { value: 'user-only', label: '仅用户' },
  { value: 'assistant-only', label: '仅助手' },
  { value: 'labeled-only', label: '仅标签' },
  { value: 'all', label: '全部' },
]

export function ThreadTreeSelector({
  entries,
  onBranch,
  pending,
}: {
  entries: HarnessSessionEntryDTO[]
  onBranch: (entry: HarnessSessionEntryDTO) => void
  pending: boolean
}) {
  const [filter, setFilter] = useState<SessionTreeFilter>('default')
  const tree = buildSessionEntryTree(entries, filter)
  return (
    <section className="thread-tree" aria-label="会话树">
      <label>
        Tree
        <select aria-label="会话树过滤" value={filter} onChange={(event) => setFilter(event.target.value as SessionTreeFilter)}>
          {FILTERS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
      </label>
      <div className="thread-tree-list">
        {tree.map(({ entry, depth, kind, preview }) => (
          <button
            key={entry.entryId}
            type="button"
            className="thread-tree-entry"
            style={{ paddingLeft: `${8 + depth * 12}px` }}
            disabled={pending || entry.parentEntryId === null && (kind === 'user' || kind === 'custom')}
            onClick={() => onBranch(entry)}
            title="从此处分支"
          >
            <span>{kind}</span>
            <strong>{preview}</strong>
          </button>
        ))}
      </div>
    </section>
  )
}
