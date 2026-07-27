import { useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { ModalBackdrop, ModalHeader } from '@/features/ai/AiConsoleModalLayout'
import {
  activeAncestry,
  branchTarget,
  buildSessionEntryTree,
  isOnActivePath,
  parseHistorySearchTokens,
  resolveSelection,
  type SessionEntryKind,
  type SessionTreeEntry,
  type SessionTreeFilter,
} from '@/features/ai/session-entry-tree'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'

const FILTERS: Array<{ value: SessionTreeFilter; label: string }> = [
  { value: 'conversation', label: '对话' },
  { value: 'all', label: '全部记录' },
]

const ENTRY_KIND_LABELS: Record<SessionEntryKind, string> = {
  user: '用户',
  assistant: '助手',
  tool: '工具',
  custom: '自定义',
  other: '系统',
}

export function HistoryBranchPanel({
  entries,
  currentHeadEntryId,
  loading,
  queryError,
  pending,
  rebindError,
  onClose,
  onRebind,
}: {
  entries: HarnessSessionEntryDTO[]
  currentHeadEntryId?: string | null
  loading: boolean
  queryError: unknown
  pending: boolean
  /** Already-formatted rebind failure (409 included); rendered verbatim so it is never swallowed. */
  rebindError: string | null
  onClose: () => void
  /** Relocates the current Thread head onto the selected Entry via PUT /threads/{id}/head. */
  onRebind: (entry: HarnessSessionEntryDTO) => void
}) {
  const [filter, setFilter] = useState<SessionTreeFilter>('conversation')
  const [searchQuery, setSearchQuery] = useState('')
  const searchTokens = useMemo(() => parseHistorySearchTokens(searchQuery), [searchQuery])
  const rows = useMemo(
    () => buildSessionEntryTree(entries, filter, searchTokens),
    [entries, filter, searchTokens],
  )
  const ancestry = useMemo(
    () => activeAncestry(entries, currentHeadEntryId ?? null),
    [entries, currentHeadEntryId],
  )
  const [selectedEntry, setSelectedEntry] = useState<HarnessSessionEntryDTO | null>(null)
  const selectedEntryRef = useRef<HTMLButtonElement | null>(null)
  const selectedEntryId = selectedEntry?.entryId

  // Default to the current head and re-attach to the nearest visible ancestor whenever the row
  // set changes (filter, search, or async-loaded entries).
  useEffect(() => {
    setSelectedEntry((current) => resolveSelection(rows, entries, current?.entryId ?? currentHeadEntryId ?? null))
  }, [rows, entries, currentHeadEntryId])

  useEffect(() => {
    selectedEntryRef.current?.scrollIntoView?.({ block: 'nearest' })
  }, [selectedEntryId])

  const canRebind = Boolean(
    selectedEntry && branchTarget(selectedEntry).headEntryId,
  ) && !loading && !pending
  const effectiveClose = pending ? () => undefined : onClose

  function changeFilter(nextFilter: SessionTreeFilter) {
    setFilter(nextFilter)
  }

  function changeSearch(next: string) {
    setSearchQuery(next)
  }

  return (
    <ModalBackdrop onClose={effectiveClose}>
      <section
        className="modal-card history-branch-modal"
        role="dialog"
        aria-modal="true"
        aria-label="历史分支"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader title="历史分支" onClose={effectiveClose} closeDisabled={pending} />
        <div className="modal-body history-branch-body">
          <div className="history-branch-controls">
            <select
              className="history-branch-filter"
              value={filter}
              aria-label="显示记录"
              disabled={pending}
              onChange={(event) => changeFilter(event.target.value as SessionTreeFilter)}
            >
              {FILTERS.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
            <label className="history-branch-search">
              <span className="sr-only">搜索记录</span>
              <input
                type="search"
                value={searchQuery}
                placeholder="搜索记录"
                aria-label="搜索记录"
                disabled={pending}
                onChange={(event) => changeSearch(event.target.value)}
              />
            </label>
          </div>
          {loading ? <div className="state-block">正在加载历史分支…</div> : null}
          {queryError ? (
            <div className="state-block danger" role="alert">历史分支加载失败</div>
          ) : null}
          {!loading && !queryError && searchTokens.length > 0 && rows.length === 0 ? (
            <div className="state-block">没有匹配 “{searchQuery.trim()}” 的记录</div>
          ) : null}
          {!loading && !queryError && searchTokens.length === 0 && rows.length === 0 ? (
            <div className="state-block">没有可显示的记录</div>
          ) : null}
          {!loading && !queryError && rows.length > 0 ? (
            <div
              className="history-branch-list"
              role="list"
              aria-label="历史列表"
            >
              {rows.map((row) => (
                <HistoryBranchRow
                  key={row.entry.entryId}
                  row={row}
                  selected={selectedEntry?.entryId === row.entry.entryId}
                  pending={pending}
                  onSelect={setSelectedEntry}
                  selectedEntryRef={selectedEntryRef}
                  isOnPath={isOnActivePath(ancestry, row.entry.entryId)}
                  isHead={row.entry.entryId === currentHeadEntryId}
                  isBranchable={Boolean(branchTarget(row.entry).headEntryId)}
                />
              ))}
            </div>
          ) : null}
          {rebindError ? <div className="state-block danger" role="alert">{rebindError}</div> : null}
        </div>
        <div className="modal-footer history-branch-footer">
          <button type="button" className="ghost-btn" onClick={effectiveClose} disabled={pending}>
            取消
          </button>
          <button
            type="button"
            className="btn-primary"
            disabled={!canRebind}
            onClick={() => selectedEntry && onRebind(selectedEntry)}
          >
            从这里继续当前 Thread
          </button>
        </div>
      </section>
    </ModalBackdrop>
  )
}

function HistoryBranchRow({
  row,
  selected,
  pending,
  onSelect,
  selectedEntryRef,
  isOnPath,
  isHead,
  isBranchable,
}: {
  row: SessionTreeEntry
  selected: boolean
  pending: boolean
  onSelect: (entry: HarnessSessionEntryDTO) => void
  selectedEntryRef: RefObject<HTMLButtonElement | null>
  isOnPath: boolean
  isHead: boolean
  isBranchable: boolean
}) {
  const connectorText = renderTreePrefix(row)
  const ariaLabel = `${ENTRY_KIND_LABELS[row.kind]} · ${row.preview}${isOnPath ? ' · 当前路径' : ''}${isHead ? ' · 当前线程位置' : ''}`
  return (
    <div role="listitem">
      <button
        type="button"
        aria-pressed={selected}
        aria-current={isHead ? 'true' : undefined}
        aria-label={ariaLabel}
        className={`history-branch-entry${selected ? ' selected' : ''}${isHead ? ' head' : ''}${isOnPath ? ' on-path' : ''}`}
        disabled={!isBranchable || pending}
        ref={selected ? selectedEntryRef : undefined}
        onClick={() => onSelect(row.entry)}
      >
        {connectorText ? <span className="history-branch-entry-glyphs" aria-hidden="true">{connectorText}</span> : null}
        <span className="history-branch-entry-kind">{ENTRY_KIND_LABELS[row.kind]}</span>
        <span className="history-branch-entry-sep" aria-hidden="true">·</span>
        <span className="history-branch-entry-preview">{row.preview}</span>
        {isOnPath ? <span className="history-branch-entry-path" aria-hidden="true">•</span> : null}
        {isHead ? <span className="history-branch-entry-head">当前线程位置</span> : null}
      </button>
    </div>
  )
}

function renderTreePrefix(row: SessionTreeEntry): string {
  if (row.depth === 0) {
    return ''
  }
  const parts: string[] = []
  for (let level = 0; level < row.depth; level += 1) {
    if (row.hasBranchConnector && level === row.depth - 1) {
      parts.push(row.isLastSibling ? '└─ ' : '├─ ')
      continue
    }
    parts.push(row.ancestorConnectors[level]?.continues ? '│  ' : '   ')
  }
  return parts.join('')
}