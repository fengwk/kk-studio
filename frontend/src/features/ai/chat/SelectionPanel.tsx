import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { Search } from 'lucide-react'
import { ThreadInteractionPanel } from '@/features/ai/runtime/thread-panel/ThreadInteractionPanel'
import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { filterReadyEnvironments } from '@/features/ai/environment/environment-utils'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { useI18n } from '@/shared/i18n'

export interface SelectionPanelItem {
  id: string
  title: string
  subtitle?: string | null
  badge?: string
  searchText?: string
}

export function SelectionPanel({
  title,
  items,
  selectedId,
  controls,
  loading = false,
  selectionPending = false,
  emptyText,
  onCycleControl,
  cycleControlHint,
  onSelect,
  onClose,
}: {
  title: string
  items: SelectionPanelItem[]
  selectedId?: string | null
  controls?: ReactNode
  loading?: boolean
  selectionPending?: boolean
  emptyText?: string
  onCycleControl?: () => void
  cycleControlHint?: string
  onSelect: (id: string) => void | Promise<void>
  onClose: () => void
}) {
  const { t } = useI18n()
  const [query, setQuery] = useState('')
  const [activeId, setActiveId] = useState<string | null>(selectedId ?? null)
  const searchRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const filteredItems = useMemo(() => {
    const tokens = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
    if (tokens.length === 0) {
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
  }, [items, query])

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

  function moveActive(delta: number) {
    if (filteredItems.length === 0) {
      return
    }
    const currentIndex = Math.max(
      0,
      filteredItems.findIndex((item) => item.id === activeId),
    )
    const nextIndex = (
      currentIndex + delta + filteredItems.length
    ) % filteredItems.length
    setActiveId(filteredItems[nextIndex]?.id ?? null)
  }

  function moveToBoundary(boundary: 'first' | 'last') {
    const next = boundary === 'first'
      ? filteredItems[0]
      : filteredItems.at(-1)
    setActiveId(next?.id ?? null)
  }

  function selectActive() {
    if (selectionPending || activeId == null) {
      return
    }
    void onSelect(activeId)
  }

  return (
    <ThreadInteractionPanel
      title={title}
      className="thread-selection-panel"
      bodyClassName="thread-selection-body"
      busy={loading || selectionPending}
      onClose={onClose}
      onKeyDown={(event) => {
        if (event.nativeEvent.isComposing || event.keyCode === 229) {
          return
        }
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          onClose()
          return
        }
        if (event.key === 'Tab' && onCycleControl) {
          event.preventDefault()
          event.stopPropagation()
          onCycleControl()
          searchRef.current?.focus({ preventScroll: true })
          return
        }
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault()
          event.stopPropagation()
          moveActive(event.key === 'ArrowDown' ? 1 : -1)
          return
        }
        if (event.key === 'PageDown' || event.key === 'PageUp') {
          event.preventDefault()
          event.stopPropagation()
          moveActive(event.key === 'PageDown' ? 8 : -8)
          return
        }
        if (event.key === 'Home' || event.key === 'End') {
          event.preventDefault()
          event.stopPropagation()
          moveToBoundary(event.key === 'Home' ? 'first' : 'last')
          return
        }
        if (event.key === 'Enter') {
          event.preventDefault()
          event.stopPropagation()
          selectActive()
        }
      }}
      controls={(
        <>
          <label className="thread-selection-search">
            <Search aria-hidden="true" />
            <span className="sr-only">{t('ai.chat.selection.search')}</span>
            <input
              ref={searchRef}
              type="search"
              value={query}
              placeholder={t('ai.chat.selection.search')}
              aria-label={t('ai.chat.selection.search')}
              disabled={selectionPending}
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          {controls}
        </>
      )}
      footer={t('ai.chat.selection.meta', {
        visible: filteredItems.length,
        total: items.length,
        cycle: cycleControlHint ? ` · ${cycleControlHint}` : '',
      })}
    >
      <ul
        ref={listRef}
        className="thread-selection-list"
        role="listbox"
        aria-label={t('ai.chat.selection.options', { title })}
      >
        {loading ? (
          <li className="thread-selection-empty">{t('ai.chat.loadingList')}</li>
        ) : null}
        {!loading && filteredItems.length === 0 ? (
          <li className="thread-selection-empty">
            {query.trim()
              ? t('ai.chat.selection.noMatch', { query: query.trim() })
              : emptyText ?? t('ai.chat.noOptions')}
          </li>
        ) : null}
        {!loading && filteredItems.map((item) => {
          const active = item.id === activeId
          const current = item.id === selectedId
          return (
            <li key={item.id}>
              <button
                type="button"
                role="option"
                aria-selected={active}
                aria-current={current ? 'true' : undefined}
                className={[
                  'thread-selection-item',
                  active ? 'active' : '',
                  current ? 'current' : '',
                ].filter(Boolean).join(' ')}
                disabled={selectionPending}
                onMouseMove={() => setActiveId(item.id)}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => void onSelect(item.id)}
              >
                <span className="thread-selection-item-title">
                  <span>{item.title}</span>
                  {current ? <em>{t('ai.chat.selected')}</em> : null}
                  {item.badge ? <em>{item.badge}</em> : null}
                </span>
                {item.subtitle ? (
                  <span className="thread-selection-item-subtitle">{item.subtitle}</span>
                ) : null}
              </button>
            </li>
          )
        })}
      </ul>
    </ThreadInteractionPanel>
  )
}

export function SelectionPanelToggleGroup({
  label,
  value,
  options,
  disabled = false,
  onChange,
}: {
  label: string
  value: string
  options: Array<{ value: string; label: string }>
  disabled?: boolean
  onChange: (value: string) => void
}) {
  return (
    <div className="thread-selection-toggle" role="group" aria-label={label}>
      <span>{label}</span>
      <div>
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            aria-pressed={option.value === value}
            disabled={disabled}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  )
}

export function AgentSelectionPanel({
  agents,
  selectedAgentName,
  loading = false,
  selectionPending = false,
  onSelect,
  onClose,
}: {
  agents: Array<{ name: string; description?: string | null }>
  selectedAgentName?: string | null
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
      loading={loading}
      selectionPending={selectionPending}
      emptyText={t('ai.chat.noAgents')}
      onSelect={onSelect}
      onClose={onClose}
    />
  )
}

export function EnvironmentSelectionPanel({
  environments,
  selectedEnvironmentName,
  selectionPending = false,
  onSelect,
  onClose,
}: {
  environments: LiveEnvironmentDTO[]
  selectedEnvironmentName?: string | null
  selectionPending?: boolean
  onSelect: (environmentName: string | null) => void | Promise<void>
  onClose: () => void
}) {
  const { t } = useI18n()
  const selected = selectedEnvironmentName?.trim() || ''
  const items: SelectionPanelItem[] = [
    {
      id: '',
      title: t('ai.chat.noneEnvironment'),
    },
    ...filterReadyEnvironments(environments).map((environment) => ({
      id: environment.name,
      title: environment.name,
      subtitle: environment.status,
    })),
  ]
  return (
    <SelectionPanel
      title={t('ai.chat.selectEnvironment')}
      items={items}
      selectedId={selected}
      selectionPending={selectionPending}
      emptyText={t('ai.chat.noEnvironments')}
      onSelect={(environmentName) => onSelect(environmentName || null)}
      onClose={onClose}
    />
  )
}

export function ThreadSelectionPanel({
  items,
  selectedThreadId,
  sort,
  loading = false,
  onSortChange,
  onSelect,
  onClose,
}: {
  items: SelectionPanelItem[]
  selectedThreadId?: string | null
  sort: PaneSortPreference
  loading?: boolean
  onSortChange: (sort: PaneSortPreference) => void
  onSelect: (threadId: string) => void | Promise<void>
  onClose: () => void
}) {
  const { t } = useI18n()
  const cycleSort = () => onSortChange(sort === 'recent' ? 'created' : 'recent')
  return (
    <SelectionPanel
      title={t('ai.chat.selectThread')}
      items={items}
      selectedId={selectedThreadId}
      loading={loading}
      emptyText={t('ai.chat.noThreads')}
      onCycleControl={cycleSort}
      cycleControlHint={t('ai.chat.selection.tabSort')}
      onSelect={onSelect}
      onClose={onClose}
      controls={(
        <SelectionPanelToggleGroup
          label={t('ai.chat.sort')}
          value={sort}
          options={[
            { value: 'recent', label: t('ai.chat.recentlyUpdated') },
            { value: 'created', label: t('ai.chat.createdAt') },
          ]}
          onChange={(value) => onSortChange(value as PaneSortPreference)}
        />
      )}
    />
  )
}
