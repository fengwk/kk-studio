import { useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
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
  /** 已格式化的 rebind 失败信息（包含 409）；原样渲染，确保不会被吞掉。 */
  rebindError: string | null
  onClose: () => void
  /** 通过 PUT /threads/{id}/head 将当前 Thread head 重定位到选中的 Entry。 */
  onRebind: (entry: HarnessSessionEntryDTO) => void
}) {
  const { t, locale } = useI18n()
  const [filter, setFilter] = useState<SessionTreeFilter>('conversation')
  const [searchQuery, setSearchQuery] = useState('')
  const searchTokens = useMemo(() => parseHistorySearchTokens(searchQuery), [searchQuery])
  const rows = useMemo(
    () => {
      void locale
      return buildSessionEntryTree(entries, filter, searchTokens)
    },
    [entries, filter, locale, searchTokens],
  )
  const ancestry = useMemo(
    () => activeAncestry(entries, currentHeadEntryId ?? null),
    [entries, currentHeadEntryId],
  )
  const [selectedEntry, setSelectedEntry] = useState<HarnessSessionEntryDTO | null>(null)
  const selectedEntryRef = useRef<HTMLButtonElement | null>(null)
  const selectedEntryId = selectedEntry?.entryId

  // 默认选中当前 head；每当行集合发生变化（filter、search 或异步加载的 entries）时，
  // 重新挂接到最近的可见祖先。
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
        aria-label={t('ai.chat.history.title')}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader title={t('ai.chat.history.title')} onClose={effectiveClose} closeDisabled={pending} />
        <div className="modal-body history-branch-body">
          <div className="history-branch-controls">
            <select
              className="history-branch-filter"
              value={filter}
              aria-label={t('ai.chat.history.filter')}
              disabled={pending}
              onChange={(event) => changeFilter(event.target.value as SessionTreeFilter)}
            >
              {FILTERS.map((item) => (
                <option key={item.value} value={item.value}>
                  {t(item.labelKey)}
                </option>
              ))}
            </select>
            <label className="history-branch-search">
              <span className="sr-only">{t('ai.chat.history.search')}</span>
              <input
                type="search"
                value={searchQuery}
                placeholder={t('ai.chat.history.search')}
                aria-label={t('ai.chat.history.search')}
                disabled={pending}
                onChange={(event) => changeSearch(event.target.value)}
              />
            </label>
          </div>
          {loading ? <div className="state-block">{t('ai.chat.history.loading')}</div> : null}
          {queryError ? (
            <div className="state-block danger" role="alert">{t('ai.chat.history.loadFailed')}</div>
          ) : null}
          {!loading && !queryError && searchTokens.length > 0 && rows.length === 0 ? (
            <div className="state-block">
              {t('ai.chat.history.noMatch', { query: searchQuery.trim() })}
            </div>
          ) : null}
          {!loading && !queryError && searchTokens.length === 0 && rows.length === 0 ? (
            <div className="state-block">{t('ai.chat.history.empty')}</div>
          ) : null}
          {!loading && !queryError && rows.length > 0 ? (
            <div
              className="history-branch-list"
              role="list"
              aria-label={t('ai.chat.history.list')}
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
            {t('ai.chat.history.cancel')}
          </button>
          <button
            type="button"
            className="btn-primary"
            disabled={!canRebind}
            onClick={() => selectedEntry && onRebind(selectedEntry)}
          >
            {t('ai.chat.history.continue')}
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
  const { t } = useI18n()
  const connectorText = renderTreePrefix(row)
  const ariaLabel = t('ai.chat.history.entryAria', {
    kind: t(ENTRY_KIND_LABEL_KEYS[row.kind]),
    preview: row.preview,
    path: isOnPath ? ` · ${t('ai.chat.history.currentPath')}` : '',
    head: isHead ? ` · ${t('ai.chat.history.currentPosition')}` : '',
  })
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
        <span className="history-branch-entry-kind">{t(ENTRY_KIND_LABEL_KEYS[row.kind])}</span>
        <span className="history-branch-entry-sep" aria-hidden="true">·</span>
        <span className="history-branch-entry-preview">{row.preview}</span>
        {isOnPath ? <span className="history-branch-entry-path" aria-hidden="true">•</span> : null}
        {isHead ? (
          <span className="history-branch-entry-head">
            {t('ai.chat.history.currentPosition')}
          </span>
        ) : null}
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