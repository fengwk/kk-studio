import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type RefObject } from 'react'
import { ThreadInteractionPanel } from '@/features/ai/runtime/thread-panel/ThreadInteractionPanel'
import {
  activeAncestry,
  buildSessionEntryTree,
  isOnActivePath,
  parseHistorySearchTokens,
  resolveSelection,
  type SessionEntryKind,
  type SessionTreeEntry,
  type SessionTreeFilter,
} from '@/features/ai/chat/session-entry-tree'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { useI18n } from '@/shared/i18n'

const FILTERS: Array<{ value: SessionTreeFilter; labelKey: string }> = [
  { value: 'conversation', labelKey: 'ai.chat.history.conversation' },
  { value: 'all', labelKey: 'ai.chat.history.allRecords' },
]

const ENTRY_KIND_LABEL_KEYS: Record<SessionEntryKind, string> = {
  user: 'ai.chat.history.user',
  assistant: 'ai.chat.history.assistant',
  tool: 'ai.chat.history.tool',
  custom: 'ai.chat.history.custom',
  other: 'ai.chat.history.system',
}

/** 选择 Entry 只切换本地 ENTRY_DRAFT，绝不调用 durable control API。 */
export function HistoryBranchPanel({
  entries,
  currentHeadEntryId,
  loading,
  queryError,
  onClose,
  onSelectEntry,
}: {
  entries: HarnessSessionEntryDTO[]
  currentHeadEntryId?: string | null
  loading: boolean
  queryError: unknown
  onClose: () => void
  onSelectEntry: (entry: HarnessSessionEntryDTO) => void
}) {
  const { t, locale } = useI18n()
  const [filter, setFilter] = useState<SessionTreeFilter>('conversation')
  const [searchQuery, setSearchQuery] = useState('')
  const tokens = useMemo(() => parseHistorySearchTokens(searchQuery), [searchQuery])
  const rows = useMemo(() => {
    void locale
    return buildSessionEntryTree(entries, filter, tokens)
  }, [entries, filter, locale, tokens])
  const ancestry = useMemo(
    () => activeAncestry(entries, currentHeadEntryId ?? null),
    [entries, currentHeadEntryId],
  )
  const [selected, setSelected] = useState<HarnessSessionEntryDTO | null>(null)
  const selectedRef = useRef<HTMLButtonElement | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const selectedId = selected?.entryId
  const selectedIndex = rows.findIndex((row) => row.entry.entryId === selectedId)

  useEffect(() => {
    setSelected((current) => resolveSelection(rows, entries, current?.entryId ?? currentHeadEntryId ?? null))
  }, [currentHeadEntryId, entries, rows])
  useEffect(() => {
    selectedRef.current?.scrollIntoView?.({ block: 'nearest' })
  }, [selectedId])
  useEffect(() => {
    searchRef.current?.focus({ preventScroll: true })
  }, [])

  function moveSelection(delta: number) {
    if (rows.length === 0) {
      return
    }
    const index = Math.max(0, selectedIndex)
    setSelected(rows[(index + delta + rows.length) % rows.length]?.entry ?? null)
  }

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onClose()
    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      event.stopPropagation()
      moveSelection(event.key === 'ArrowDown' ? 1 : -1)
    } else if (event.key === 'Enter' && selected) {
      event.preventDefault()
      event.stopPropagation()
      onSelectEntry(selected)
    }
  }

  return (
    <ThreadInteractionPanel
      title={t('ai.chat.history.title')}
      className="history-branch-panel"
      bodyClassName="history-branch-body"
      busy={loading}
      onClose={onClose}
      onKeyDown={onKeyDown}
      controls={(
        <div className="history-branch-controls">
          <select
            className="history-branch-filter"
            value={filter}
            aria-label={t('ai.chat.history.filter')}
            onChange={(event) => setFilter(event.target.value as SessionTreeFilter)}
          >
            {FILTERS.map((item) => <option key={item.value} value={item.value}>{t(item.labelKey)}</option>)}
          </select>
          <label className="history-branch-search">
            <span className="sr-only">{t('ai.chat.history.search')}</span>
            <input
              ref={searchRef}
              type="search"
              value={searchQuery}
              placeholder={t('ai.chat.history.search')}
              aria-label={t('ai.chat.history.search')}
              onChange={(event) => setSearchQuery(event.target.value)}
            />
          </label>
        </div>
      )}
      footer={(
        <div className="history-branch-footer">
          <span className="history-branch-hint">
            {t('ai.chat.history.hint', {
              current: selectedIndex < 0 ? 0 : selectedIndex + 1,
              total: rows.length,
            })}
          </span>
          <div className="history-branch-actions">
            <button type="button" className="ghost-btn" onClick={onClose}>
              {t('ai.chat.history.cancel')}
            </button>
            <button
              type="button"
              className="btn-primary"
              disabled={loading || selected == null}
              onClick={() => selected && onSelectEntry(selected)}
            >
              {t('ai.chat.history.continue')}
            </button>
          </div>
        </div>
      )}
    >
      {loading ? <div className="state-block">{t('ai.chat.history.loading')}</div> : null}
      {queryError ? <div className="state-block danger" role="alert">{t('ai.chat.history.loadFailed')}</div> : null}
      {!loading && !queryError && rows.length === 0 ? (
        <div className="state-block">
          {tokens.length > 0
            ? t('ai.chat.history.noMatch', { query: searchQuery.trim() })
            : t('ai.chat.history.empty')}
        </div>
      ) : null}
      {!loading && !queryError && rows.length > 0 ? (
        <div className="history-branch-list" role="list" aria-label={t('ai.chat.history.list')}>
          {rows.map((row) => (
            <HistoryRow
              key={row.entry.entryId}
              row={row}
              selected={row.entry.entryId === selectedId}
              selectedRef={selectedRef}
              isOnPath={isOnActivePath(ancestry, row.entry.entryId)}
              isHead={row.entry.entryId === currentHeadEntryId}
              onSelect={setSelected}
            />
          ))}
        </div>
      ) : null}
    </ThreadInteractionPanel>
  )
}

function HistoryRow({
  row,
  selected,
  selectedRef,
  isOnPath,
  isHead,
  onSelect,
}: {
  row: SessionTreeEntry
  selected: boolean
  selectedRef: RefObject<HTMLButtonElement | null>
  isOnPath: boolean
  isHead: boolean
  onSelect: (entry: HarnessSessionEntryDTO) => void
}) {
  const { t } = useI18n()
  return (
    <div role="listitem">
      <button
        type="button"
        aria-pressed={selected}
        aria-current={isHead ? 'true' : undefined}
        aria-label={t('ai.chat.history.entryAria', {
          kind: t(ENTRY_KIND_LABEL_KEYS[row.kind]),
          preview: row.preview,
          path: isOnPath ? ` · ${t('ai.chat.history.currentPath')}` : '',
          head: isHead ? ` · ${t('ai.chat.history.currentPosition')}` : '',
        })}
        className={`history-branch-entry${selected ? ' selected' : ''}${isHead ? ' head' : ''}${isOnPath ? ' on-path' : ''}`}
        ref={selected ? selectedRef : undefined}
        onClick={() => onSelect(row.entry)}
      >
        <span className="history-branch-entry-cursor" aria-hidden="true">{selected ? '›' : ''}</span>
        <span className="history-branch-entry-glyphs" aria-hidden="true">{treePrefix(row)}</span>
        {isOnPath ? <span className="history-branch-entry-path" aria-hidden="true">•</span> : null}
        <span className="history-branch-entry-kind">{t(ENTRY_KIND_LABEL_KEYS[row.kind])}</span>
        <span className="history-branch-entry-sep" aria-hidden="true">·</span>
        <span className="history-branch-entry-preview">{row.preview}</span>
        {isHead ? <span className="history-branch-entry-head">{t('ai.chat.history.currentPosition')}</span> : null}
      </button>
    </div>
  )
}

function treePrefix(row: SessionTreeEntry): string {
  if (row.depth === 0) {
    return ''
  }
  const parts: string[] = []
  for (let level = 0; level < row.depth; level += 1) {
    if (row.hasBranchConnector && level === row.depth - 1) {
      parts.push(row.isLastSibling ? '└─ ' : '├─ ')
    } else {
      parts.push(row.ancestorConnectors[level]?.continues ? '│  ' : '   ')
    }
  }
  return parts.join('')
}
