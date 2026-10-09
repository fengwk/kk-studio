import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type RefObject } from 'react'
import { ThreadInteractionPanel } from '@/features/ai/runtime/thread-panel/ThreadInteractionPanel'
import {
  buildHistoryTree,
  highlightSegments,
  historyRowMatches,
  historySearchTokens,
  type HistoryEntryKind,
  type HistoryTreeRow,
} from '@/features/ai/chat/history-tree'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import '@/features/ai/chat/history-tree.css'

const KIND_LABEL_KEYS: Record<HistoryEntryKind, string> = {
  root: 'ai.chat.branch.kindRoot',
  user: 'ai.chat.history.user',
  assistant: 'ai.chat.history.assistant',
  tool: 'ai.chat.history.tool',
  custom: 'ai.chat.history.custom',
  notification: 'ai.chat.branch.kindNotification',
  other: 'ai.chat.history.system',
}

/**
 * 历史树：一行一个真实 Entry，lane 只表达真实父子分叉，不含目录式缩进、卡片、
 * Thread 状态或重组后的 Turn 结构。
 *
 * 选中任意 Entry 只改变本地查看焦点（绝不调用 durable control API）；只有 ROOT
 * 与已关闭 TURN_END 才能“从此处分支”或“从此处新建会话”，其它 Entry 的动作禁用并
 * 给出原因。会话 fork 是显式动作：新 Session 无用户可见名称，切点与来源 Thread 由
 * 目标 pane 的首次输入原子提交。搜索只做定位与高亮，绝不隐藏行或改变图形。
 */
export function HistoryTree({
  entries,
  headEntryId,
  loading,
  queryError,
  onClose,
  onFork,
  onForkSession,
}: {
  entries: HarnessSessionEntryDTO[]
  headEntryId?: string | null
  loading: boolean
  queryError: unknown
  onClose: () => void
  onFork: (entry: HarnessSessionEntryDTO) => void
  /** 显式“从此处新建会话”：缺失时（只读/非 Chat 宿主）不渲染该动作。 */
  onForkSession?: (entry: HarnessSessionEntryDTO) => void
}) {
  const { t } = useI18n()
  const [searchQuery, setSearchQuery] = useState('')
  const tokens = useMemo(() => historySearchTokens(searchQuery), [searchQuery])
  const rows = useMemo(
    () => buildHistoryTree(entries, { headEntryId: headEntryId ?? null }),
    [entries, headEntryId],
  )
  const matchedIds = useMemo(
    () => new Set(rows.filter((row) => historyRowMatches(row, tokens)).map((row) => row.entry.entryId)),
    [rows, tokens],
  )
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selectedRef = useRef<HTMLButtonElement | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const selectedRow = rows.find((row) => row.entry.entryId === selectedId) ?? null
  const selectedIndex = selectedRow == null ? -1 : rows.indexOf(selectedRow)

  useEffect(() => {
    setSelectedId((current) => resolveRowId(rows, current, headEntryId ?? null))
  }, [rows, headEntryId])
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
    setSelectedId(rows[(index + delta + rows.length) % rows.length]?.entry.entryId ?? null)
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
    } else if (event.key === 'Enter' && selectedRow?.canFork) {
      event.preventDefault()
      event.stopPropagation()
      onFork(selectedRow.entry)
    }
  }

  const matchCount = matchedIds.size
  return (
    <ThreadInteractionPanel
      title={t('ai.chat.branch.historyTree')}
      className="history-tree-panel"
      bodyClassName="history-tree-body"
      busy={loading}
      onClose={onClose}
      onKeyDown={onKeyDown}
      controls={(
        <label className="history-tree-search">
          <span className="sr-only">{t('ai.chat.history.search')}</span>
          <TextInput
            ref={searchRef}
            type="search"
            value={searchQuery}
            placeholder={t('ai.chat.history.search')}
            aria-label={t('ai.chat.history.search')}
            onChange={(event) => setSearchQuery(event.target.value)}
          />
        </label>
      )}
      footer={(
        <div className="history-tree-footer">
          <span className="history-tree-hint">
            {t('ai.chat.history.hint', {
              current: selectedIndex < 0 ? 0 : selectedIndex + 1,
              total: rows.length,
            })}
          </span>
          {selectedRow != null && !selectedRow.canFork ? (
            <span className="history-tree-disabled-reason" role="note">
              {t('ai.chat.branch.notBoundary')}
            </span>
          ) : null}
          <div className="history-tree-actions">
            <Button variant="ghost" onClick={onClose}>
              {t('ai.chat.history.cancel')}
            </Button>
            {onForkSession ? (
              <Button
                variant="ghost"
                disabled={loading || selectedRow?.canFork !== true}
                title={selectedRow?.canFork === false ? t('ai.chat.branch.notBoundary') : undefined}
                onClick={() => selectedRow?.canFork && onForkSession(selectedRow.entry)}
              >
                {t('ai.chat.branch.forkSession')}
              </Button>
            ) : null}
            <Button
              disabled={loading || selectedRow?.canFork !== true}
              title={selectedRow?.canFork === false ? t('ai.chat.branch.notBoundary') : undefined}
              onClick={() => selectedRow?.canFork && onFork(selectedRow.entry)}
            >
              {t('ai.chat.branch.fromHere')}
            </Button>
          </div>
        </div>
      )}
    >
      {loading ? <div className="state-block">{t('ai.chat.history.loading')}</div> : null}
      {queryError ? <div className="state-block danger" role="alert">{t('ai.chat.history.loadFailed')}</div> : null}
      {!loading && !queryError && rows.length === 0 ? (
        <div className="state-block">{t('ai.chat.history.empty')}</div>
      ) : null}
      {!loading && !queryError && rows.length > 0 ? (
        <>
          {tokens.length > 0 && matchCount === 0 ? (
            <div className="history-tree-no-match">
              {t('ai.chat.history.noMatch', { query: searchQuery.trim() })}
            </div>
          ) : null}
          <div className="history-tree-list" role="list" aria-label={t('ai.chat.branch.historyTree')}>
            {rows.map((row) => (
              <HistoryTreeRowView
                key={row.entry.entryId}
                row={row}
                tokens={tokens}
                dimmed={tokens.length > 0 && !matchedIds.has(row.entry.entryId)}
                selected={row.entry.entryId === selectedId}
                selectedRef={selectedRef}
                onSelect={setSelectedId}
              />
            ))}
          </div>
        </>
      ) : null}
    </ThreadInteractionPanel>
  )
}

/** 默认选中 head，其次保留仍然存在的选择，最后回落到最后一行。 */
function resolveRowId(
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

/** lane gutter 字形：每个 lane 一列，节点列画节点，其余列按连通性画竖线。 */
function historyLaneGlyphs(row: HistoryTreeRow): string {
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

function HistoryTreeRowView({
  row,
  tokens,
  dimmed,
  selected,
  selectedRef,
  onSelect,
}: {
  row: HistoryTreeRow
  tokens: string[]
  dimmed: boolean
  selected: boolean
  selectedRef: RefObject<HTMLButtonElement | null>
  onSelect: (entryId: string) => void
}) {
  const { t } = useI18n()
  const kindLabel = t(KIND_LABEL_KEYS[row.kind])
  const segments = tokens.length > 0 ? highlightSegments(row.preview, tokens) : [{ text: row.preview, match: false }]
  return (
    <div role="listitem">
      <button
        type="button"
        aria-pressed={selected}
        aria-current={row.isHead ? 'true' : undefined}
        aria-label={t('ai.chat.history.entryAria', {
          kind: kindLabel,
          preview: row.preview,
          path: row.onActivePath ? ` · ${t('ai.chat.history.currentPath')}` : '',
          head: row.isHead ? ` · ${t('ai.chat.history.currentPosition')}` : '',
        })}
        data-lane={row.lane}
        data-fork={row.isFork ? 'true' : undefined}
        data-can-fork={row.canFork ? 'true' : 'false'}
        className={[
          'history-tree-entry',
          selected ? 'selected' : '',
          row.isHead ? 'head' : '',
          row.onActivePath ? 'on-path' : '',
          dimmed ? 'dimmed' : '',
        ].filter(Boolean).join(' ')}
        ref={selected ? selectedRef : undefined}
        onClick={() => onSelect(row.entry.entryId)}
      >
        <span className="history-tree-entry-cursor" aria-hidden="true">{selected ? '›' : ''}</span>
        <span className="history-tree-entry-lane" aria-hidden="true">{historyLaneGlyphs(row)}</span>
        {row.onActivePath ? <span className="history-tree-entry-path" aria-hidden="true">•</span> : null}
        <span className="history-tree-entry-kind">{kindLabel}</span>
        <span className="history-tree-entry-sep" aria-hidden="true">·</span>
        <span className="history-tree-entry-preview">
          {segments.map((segment, index) => segment.match
            ? <mark key={index} className="history-tree-mark">{segment.text}</mark>
            : <span key={index}>{segment.text}</span>)}
        </span>
        {row.isHead ? (
          <span className="history-tree-entry-head">{t('ai.chat.history.currentPosition')}</span>
        ) : null}
      </button>
    </div>
  )
}
