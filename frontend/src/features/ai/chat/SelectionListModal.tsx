import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { filterReadyEnvironments } from '@/features/ai/environment/environment-utils'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { useI18n } from '@/shared/i18n'

export interface SelectionListItem {
  id: string
  title: string
  subtitle?: string
  badge?: string
}

export type SelectionScope = 'current' | 'global'

export function SelectionListModal({
  open,
  title,
  items,
  sort,
  onSortChange,
  showSort = true,
  onSelect,
  onClose,
  emptyText,
  scope,
  onScopeChange,
  loading = false,
  hasMore = false,
  loadingMore = false,
  onLoadMore,
  selectionPending = false,
}: {
  open: boolean
  title: string
  items: SelectionListItem[]
  sort: PaneSortPreference
  onSortChange: (sort: PaneSortPreference) => void
  showSort?: boolean
  onSelect: (id: string) => void | Promise<void>
  onClose: () => void
  emptyText?: string
  scope?: SelectionScope
  onScopeChange?: (scope: SelectionScope) => void
  loading?: boolean
  hasMore?: boolean
  loadingMore?: boolean
  onLoadMore?: () => void
  selectionPending?: boolean
}) {
  const { t } = useI18n()
  if (!open) {
    return null
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <div className="modal-card selection-modal" aria-label={title} onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title={title} onClose={onClose} />
        <div className="modal-body">
          {onScopeChange || showSort ? (
            <div className="selection-controls-row">
              {onScopeChange ? (
                <div className="selection-sort-row">
                  <span>{t('ai.chat.scope')}</span>
                  <div className="selection-sort-actions">
                    <button
                      type="button"
                      className={scope === 'current' ? 'active' : undefined}
                      onClick={() => onScopeChange('current')}
                      disabled={selectionPending}
                    >
                      {t('ai.chat.currentChat')}
                    </button>
                    <button
                      type="button"
                      className={scope === 'global' ? 'active' : undefined}
                      onClick={() => onScopeChange('global')}
                      disabled={selectionPending}
                    >
                      {t('ai.chat.globalThread')}
                    </button>
                  </div>
                </div>
              ) : null}
              {showSort ? (
                <div className="selection-sort-row">
                  <span>{t('ai.chat.sort')}</span>
                  <div className="selection-sort-actions">
                    <button
                      type="button"
                      className={sort === 'recent' ? 'active' : undefined}
                      onClick={() => onSortChange('recent')}
                    >
                      {t('ai.chat.recentlyUpdated')}
                    </button>
                    <button
                      type="button"
                      className={sort === 'created' ? 'active' : undefined}
                      onClick={() => onSortChange('created')}
                    >
                      {t('ai.chat.createdAt')}
                    </button>
                  </div>
                </div>
              ) : null}
            </div>
          ) : null}
          <ul className="selection-list" aria-busy={loading || selectionPending}>
            {loading ? <li className="selection-empty">{t('ai.chat.loadingList')}</li> : null}
            {!loading && items.length === 0 ? (
              <li className="selection-empty">{emptyText ?? t('ai.chat.noOptions')}</li>
            ) : null}
            {items.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  className="selection-item"
                  disabled={selectionPending}
                  onClick={() => void onSelect(item.id)}
                >
                  <span className="selection-item-title">
                    {item.title}
                    {item.badge ? <em>{item.badge}</em> : null}
                  </span>
                  {item.subtitle ? <span className="selection-item-subtitle">{item.subtitle}</span> : null}
                </button>
              </li>
            ))}
          </ul>
          {hasMore && onLoadMore ? (
            <button
              type="button"
              className="selection-load-more"
              onClick={onLoadMore}
              disabled={loadingMore || selectionPending}
            >
              {loadingMore ? t('ai.chat.loadingList') : t('ai.chat.loadMore')}
            </button>
          ) : null}
        </div>
      </div>
    </ModalBackdrop>
  )
}

export function AgentSelectionModal({
  open,
  agents,
  onSelect,
  onClose,
}: {
  open: boolean
  agents: Array<{ id: string; name: string; description?: string | null }>
  onSelect: (agentId: string) => void
  onClose: () => void
}) {
  const { t } = useI18n()
  if (!open) {
    return null
  }
  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="modal-card selection-modal"
        aria-label={t('ai.chat.selectAgentTitle')}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader title={t('ai.chat.selectAgentTitle')} onClose={onClose} />
        <div className="modal-body">
          <ul className="selection-list">
            {agents.length === 0 ? (
              <li className="selection-empty">{t('ai.chat.noAgents')}</li>
            ) : null}
            {agents.map((agent) => (
              <li key={agent.id}>
                <button type="button" className="selection-item" onClick={() => onSelect(agent.id)}>
                  <span className="selection-item-title">{agent.name}</span>
                  {agent.description ? <span className="selection-item-subtitle">{agent.description}</span> : null}
                </button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </ModalBackdrop>
  )
}

export function EnvironmentSelectionModal({
  open,
  environments,
  selectedEnvironmentName,
  selectionPending = false,
  onSelect,
  onClose,
}: {
  open: boolean
  environments: LiveEnvironmentDTO[]
  selectedEnvironmentName?: string | null
  selectionPending?: boolean
  onSelect: (environmentName: string | null) => void | Promise<void>
  onClose: () => void
}) {
  const { t } = useI18n()
  const selected = selectedEnvironmentName?.trim() || ''
  const items: SelectionListItem[] = [
    {
      id: '',
      title: t('ai.chat.noneEnvironment'),
      badge: selected ? undefined : t('ai.chat.selected'),
    },
    ...filterReadyEnvironments(environments).map((environment) => ({
      id: environment.name,
      title: environment.name,
      badge: environment.name === selected ? t('ai.chat.selected') : undefined,
    })),
  ]
  return (
    <SelectionListModal
      open={open}
      title={t('ai.chat.selectEnvironment')}
      items={items}
      sort="recent"
      onSortChange={() => undefined}
      showSort={false}
      selectionPending={selectionPending}
      emptyText={t('ai.chat.noEnvironments')}
      onClose={onClose}
      onSelect={(environmentName) => onSelect(environmentName || null)}
    />
  )
}
