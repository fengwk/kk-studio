import { Pencil } from 'lucide-react'
import { SearchHitText } from '@/features/thread/SearchHitText'

export interface SelectionPanelItem {
  id: string
  title: string
  subtitle?: string | null
  badge?: string
  searchText?: string
}

export interface SelectionRowProps {
  item: SelectionPanelItem
  optionId?: string
  active: boolean
  current: boolean
  disabled: boolean
  /** 当前项旁的“已选”文案。 */
  selectedLabel: string
  /** 行内重命名动作的 aria-label；缺省时不渲染该动作。 */
  renameLabel?: string
  /** 当前搜索的小写 token；为空时不高亮。 */
  searchTokens: string[]
  onHover: () => void
  onSelect: () => void
  onRename?: () => void
}

/**
 * 选择面板的一行。
 *
 * 行本身是 `role=option` 按钮：鼠标按下不抢焦点（否则会打断搜索框输入），
 * hover/move 只登记活动项。重命名动作是行内独立按钮，Enter/空格不冒泡成整行提交。
 * 标题与副标题按可用宽度单行省略，搜索时优先显示命中词。
 */
export function SelectionRow({
  item,
  optionId,
  active,
  current,
  disabled,
  selectedLabel,
  renameLabel,
  searchTokens,
  onHover,
  onSelect,
  onRename,
}: SelectionRowProps) {
  return (
    <li className="thread-selection-row">
      <button
        type="button"
        role="option"
        id={optionId}
        tabIndex={optionId == null ? undefined : -1}
        aria-label={[item.title, current ? selectedLabel : '', item.badge, item.subtitle].filter(Boolean).join(' ')}
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
          <SearchHitText text={item.title} tokens={searchTokens} markClassName="thread-selection-mark" />
          {current ? <em>{selectedLabel}</em> : null}
          {item.badge ? <code title={item.badge}>{item.badge}</code> : null}
        </span>
        {item.subtitle ? (
          <span className="thread-selection-item-subtitle">
            <SearchHitText text={item.subtitle} tokens={searchTokens} markClassName="thread-selection-mark" />
          </span>
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
