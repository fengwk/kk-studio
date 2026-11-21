import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { Search } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { ThreadInteractionPanel } from '@/features/thread/runtime/thread-panel/ThreadInteractionPanel'
import { SelectionLoadMoreRow, SelectionRow, type SelectionPanelItem } from '@/features/thread/SelectionRow'
import { useSelectionKeyboard } from '@/features/thread/useSelectionKeyboard'
import { useI18n } from '@/shared/i18n'
import './thread-selection.css'

export type { SelectionPanelItem }

export interface SelectionPanelProps {
  title: string
  inlineId?: string
  catalogError?: boolean
  /** 列表查询失败：展示错误与重试入口，绝不用空态文案掩盖失败。 */
  loadError?: string | null
  onRetryLoad?: () => void
  selectionError?: string | null
  items: SelectionPanelItem[]
  selectedId?: string | null
  controls?: ReactNode
  loading?: boolean
  selectionPending?: boolean
  /** 行内重命名动作的 aria-label；缺省时不渲染该动作。 */
  renameLabel?: string
  emptyText?: string
  /** 是否还有未加载的选项页；与 onLoadMore 同时给出才渲染加载更多入口。 */
  hasMore?: boolean
  loadingMore?: boolean
  onLoadMore?: () => void
  onCycleControl?: () => void
  cycleControlHint?: string
  onRename?: (id: string) => void
  onSelect: (id: string) => void | Promise<void>
  onClose: () => void
}

/** 搜索框里的查询按空白拆分为小写 token；空查询返回 null 表示不过滤。 */
function searchTokensOf(query: string): string[] | null {
  const tokens = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return tokens.length > 0 ? tokens : null
}

function filterItems(items: SelectionPanelItem[], tokens: string[] | null): SelectionPanelItem[] {
  if (tokens == null) {
    return items
  }
  return items.filter((item) => {
    const haystack = [
      item.id,
      item.title,
      item.subtitle,
      item.badge,
      item.searchText,
    ].filter(Boolean).join(' ').toLocaleLowerCase()
    return tokens.every((token) => haystack.includes(token))
  })
}

/**
 * 可搜索的单选面板：Session、Agent、Thread 等选择场景共用的键盘与无障碍语义。
 *
 * 活动项（键盘焦点行）优先保持在当前选择上；被过滤掉时依次回退到 selectedId
 * （若仍可见）与第一项。搜索只影响列表，不改变已选值与提交结果。
 */
export function SelectionPanel({
  title,
  inlineId,
  catalogError = false,
  loadError,
  onRetryLoad,
  selectionError,
  items,
  selectedId,
  controls,
  loading = false,
  selectionPending = false,
  renameLabel,
  emptyText,
  hasMore = false,
  loadingMore = false,
  onLoadMore,
  onCycleControl,
  cycleControlHint,
  onRename,
  onSelect,
  onClose,
}: SelectionPanelProps) {
  const { t } = useI18n()
  const [query, setQuery] = useState('')
  const [activeId, setActiveId] = useState<string | null>(selectedId ?? null)
  const searchRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const loadFailed = !loading && loadError != null
  const filteredItems = useMemo(
    () => loading || catalogError || loadFailed ? [] : filterItems(items, searchTokensOf(query)),
    [items, query, loading, catalogError, loadFailed],
  )
  const onKeyDown = useSelectionKeyboard({
    filteredItems,
    activeId,
    setActiveId,
    selectionPending,
    onSelect,
    onClose,
    onCycleControl,
    searchRef,
  })

  useEffect(() => {
    searchRef.current?.focus({ preventScroll: true })
  }, [])

  useEffect(() => {
    setActiveId((current) => {
      if (current != null && filteredItems.some((item) => item.id === current)) {
        return current
      }
      if (
        selectedId != null
        && filteredItems.some((item) => item.id === selectedId)
      ) {
        return selectedId
      }
      return filteredItems[0]?.id ?? null
    })
  }, [filteredItems, selectedId])

  useEffect(() => {
    const active = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]')
    active?.scrollIntoView({ block: 'nearest' })
  }, [activeId])

  const renameItem = onRename == null ? undefined : (id: string) => {
    setActiveId(id)
    onRename(id)
  }

  const listId = inlineId == null ? undefined : `${inlineId}-list`
  const activeIndex = filteredItems.findIndex((item) => item.id === activeId)
  const searchControls = (
    <>
      <label className="thread-selection-search">
        <Search aria-hidden="true" />
        <span className="sr-only">{t('ai.chat.selection.search')}</span>
        <input
          ref={searchRef}
          type="search"
          className={inlineId == null ? undefined : 'ui-search-input'}
          role={inlineId == null ? undefined : 'combobox'}
          aria-expanded={inlineId == null ? undefined : true}
          aria-controls={listId}
          aria-autocomplete={inlineId == null ? undefined : 'list'}
          aria-activedescendant={listId != null && activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined}
          value={query}
          placeholder={t('ai.chat.selection.search')}
          aria-label={t('ai.chat.selection.search')}
          disabled={selectionPending}
          onChange={(event) => setQuery(event.target.value)}
        />
      </label>
      {controls}
    </>
  )
  const feedback = loadFailed
    ? null
    : catalogError
      ? t('ai.chat.agentCatalogError')
      : loading
        ? t('ai.chat.loadingList')
        : filteredItems.length === 0
          ? query.trim()
            ? t('ai.chat.selection.noMatch', { query: query.trim() })
            : emptyText ?? t('ai.chat.noOptions')
          : null
  const content = (
    <>
      {selectionError ? <div className="field-error" role="alert">{selectionError}</div> : null}
      {loadFailed ? (
        <div className="thread-selection-error" role="alert">
          <span>{loadError}</span>
          {onRetryLoad ? (
            <Button variant="ghost" disabled={selectionPending} onClick={onRetryLoad}>
              {t('shared.conflict.retry')}
            </Button>
          ) : null}
        </div>
      ) : null}
      {feedback ? <div className="thread-selection-empty" role="status">{feedback}</div> : null}
      <ul
        ref={listRef}
        id={listId}
        className="thread-selection-list"
        role="listbox"
        aria-label={t('ai.chat.selection.options', { title })}
      >
        {filteredItems.map((item, index) => (
          <SelectionRow
            key={item.id}
            item={item}
            optionId={listId == null ? undefined : `${listId}-${index}`}
            active={item.id === activeId}
            current={item.id === selectedId}
            disabled={selectionPending}
            selectedLabel={t('ai.chat.selected')}
            renameLabel={renameLabel}
            onHover={() => setActiveId(item.id)}
            onSelect={() => void onSelect(item.id)}
            onRename={renameItem == null ? undefined : () => renameItem(item.id)}
          />
        ))}
        {!loading && !catalogError && !loadFailed && hasMore && onLoadMore ? (
          <SelectionLoadMoreRow
            label={loadingMore ? t('ai.chat.loadingList') : t('ai.chat.loadMore')}
            disabled={selectionPending || loadingMore}
            onLoadMore={onLoadMore}
          />
        ) : null}
      </ul>
    </>
  )

  if (inlineId != null) {
    return (
      <div id={inlineId} role="group" aria-label={title} className="agent-select-options" onKeyDown={onKeyDown}>
        {searchControls}
        {content}
      </div>
    )
  }

  return (
    <ThreadInteractionPanel
      title={title}
      className="thread-selection-panel"
      bodyClassName="thread-selection-body"
      busy={loading || selectionPending}
      onClose={onClose}
      onKeyDown={onKeyDown}
      controls={searchControls}
      footer={t('ai.chat.selection.meta', {
        visible: filteredItems.length,
        total: items.length,
        cycle: cycleControlHint ? ` · ${cycleControlHint}` : '',
      })}
    >
      {content}
    </ThreadInteractionPanel>
  )
}

/** Agent 选择面板：把 Agent 目录投影成统一选择项，标题与空态文案由面板模板给出。 */
export function AgentSelectionPanel({
  agents,
  selectedAgentName,
  inlineId,
  catalogError,
  selectionError,
  loading = false,
  selectionPending = false,
  onSelect,
  onClose,
}: {
  agents: readonly { name: string; description?: string | null }[]
  selectedAgentName?: string | null
  inlineId?: string
  catalogError?: boolean
  selectionError?: string | null
  loading?: boolean
  selectionPending?: boolean
  onSelect: (agentName: string) => void | Promise<void>
  onClose: () => void
}) {
  const { t } = useI18n()
  return (
    <SelectionPanel
      title={t('ai.chat.selectAgentTitle')}
      items={agents.map((agent) => ({
        id: agent.name,
        title: agent.name,
        subtitle: agent.description,
      }))}
      selectedId={selectedAgentName}
      inlineId={inlineId}
      catalogError={catalogError}
      selectionError={selectionError}
      loading={loading}
      selectionPending={selectionPending}
      emptyText={t('ai.chat.noAgents')}
      onSelect={onSelect}
      onClose={onClose}
    />
  )
}
