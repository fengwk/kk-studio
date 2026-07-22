import { ModalBackdrop, ModalHeader } from '@/features/ai/AiConsoleModalLayout'
import type { PaneSortPreference } from '@/features/ai/chat-pane-state'

export interface SelectionListItem {
  id: string
  title: string
  subtitle?: string
  badge?: string
}

export function SelectionListModal({
  open,
  title,
  items,
  sort,
  onSortChange,
  showSort = true,
  onSelect,
  onClose,
  emptyText = '暂无选项',
}: {
  open: boolean
  title: string
  items: SelectionListItem[]
  sort: PaneSortPreference
  onSortChange: (sort: PaneSortPreference) => void
  showSort?: boolean
  onSelect: (id: string) => void
  onClose: () => void
  emptyText?: string
}) {
  if (!open) {
    return null
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <div className="modal-card selection-modal" aria-label={title} onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title={title} onClose={onClose} />
        <div className="modal-body">
          {showSort ? (
            <div className="selection-sort-row">
              <span>排序</span>
              <div className="selection-sort-actions">
                <button
                  type="button"
                  className={sort === 'recent' ? 'active' : undefined}
                  onClick={() => onSortChange('recent')}
                >
                  最近更新
                </button>
                <button
                  type="button"
                  className={sort === 'created' ? 'active' : undefined}
                  onClick={() => onSortChange('created')}
                >
                  创建时间
                </button>
              </div>
            </div>
          ) : null}
          <ul className="selection-list">
            {items.length === 0 ? <li className="selection-empty">{emptyText}</li> : null}
            {items.map((item) => (
              <li key={item.id}>
                <button type="button" className="selection-item" onClick={() => onSelect(item.id)}>
                  <span className="selection-item-title">
                    {item.title}
                    {item.badge ? <em>{item.badge}</em> : null}
                  </span>
                  {item.subtitle ? <span className="selection-item-subtitle">{item.subtitle}</span> : null}
                </button>
              </li>
            ))}
          </ul>
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
  if (!open) {
    return null
  }
  return (
    <ModalBackdrop onClose={onClose}>
      <div className="modal-card selection-modal" aria-label="选择 Agent" onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title="选择 Agent" onClose={onClose} />
        <div className="modal-body">
          <ul className="selection-list">
            {agents.length === 0 ? <li className="selection-empty">暂无可用 Agent</li> : null}
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
