import { Pencil } from 'lucide-react'

export interface SelectionPanelItem {
  id: string
  title: string
  subtitle?: string | null
  badge?: string
  searchText?: string
}

export interface SelectionRowProps {
  item: SelectionPanelItem
  active: boolean
  current: boolean
  disabled: boolean
  /** 当前项旁的“已选”文案。 */
  selectedLabel: string
  /** 行内重命名动作的 aria-label；缺省时不渲染该动作。 */
  renameLabel?: string
  onHover: () => void
  onSelect: () => void
  onRename?: () => void
}

/**
 * 选择面板的一行。
 *
 * 行本身是 `role=option` 按钮：鼠标按下不抢焦点（否则会打断搜索框输入），
 * hover/move 只登记活动项。重命名动作是行内独立按钮，Enter/空格不冒泡成整行提交。
 */
export function SelectionRow({
  item,
  active,
  current,
  disabled,
  selectedLabel,
  renameLabel,
  onHover,
  onSelect,
  onRename,
}: SelectionRowProps) {
  return (
    <li className="thread-selection-row">
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
        disabled={disabled}
        onMouseMove={onHover}
        onMouseDown={(event) => event.preventDefault()}
        onClick={onSelect}
      >
        <span className="thread-selection-item-title">
          <span>{item.title}</span>
          {current ? <em>{selectedLabel}</em> : null}
          {item.badge ? <em>{item.badge}</em> : null}
        </span>
        {item.subtitle ? (
          <span className="thread-selection-item-subtitle">{item.subtitle}</span>
        ) : null}
      </button>
      {onRename && renameLabel ? (
        <button
          type="button"
          className="thread-selection-rename"
          aria-label={renameLabel}
          title={renameLabel}
          disabled={disabled}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.stopPropagation()
            }
          }}
          onClick={(event) => {
            event.stopPropagation()
            onRename()
          }}
        >
          <Pencil aria-hidden="true" />
        </button>
      ) : null}
    </li>
  )
}

export interface SelectionLoadMoreRowProps {
  label: string
  disabled: boolean
  onLoadMore: () => void
}

/** 未加载选项页的入口：与普通行同形，但不参与键盘活动项。 */
export function SelectionLoadMoreRow({ label, disabled, onLoadMore }: SelectionLoadMoreRowProps) {
  return (
    <li className="thread-selection-row">
      <button
        type="button"
        className="thread-selection-item"
        disabled={disabled}
        onMouseDown={(event) => event.preventDefault()}
        onClick={onLoadMore}
      >
        <span className="thread-selection-item-title">
          <span>{label}</span>
        </span>
      </button>
    </li>
  )
}
