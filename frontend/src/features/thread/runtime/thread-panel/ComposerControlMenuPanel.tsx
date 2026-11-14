import { Check, ChevronLeft } from 'lucide-react'
import type { KeyboardEvent, RefObject } from 'react'
import type { SelectableItem } from '@/features/thread/runtime/thread-panel/composer-control-menus'
import type { ThreadComposerControlMenu } from '@/features/thread/runtime/thread-panel/ThreadComposerControls'

/**
 * 底栏的 anchored menu：permission/environment/model 的单级列表，model 附带搜索框，
 * variant 是 model 的第二级（带返回按钮）。
 *
 * 纯展示：键盘游标、查询与选中结果都由 {@link useComposerControlMenu} 持有；
 * 这里只把既成事实映射成 listbox，并把按键与指针原样上报。
 */
export function ComposerControlMenuPanel({
  menu,
  items,
  activeId,
  query,
  listLabel,
  backLabel,
  search,
  hint,
  currentOf,
  listRef,
  searchRef,
  onQueryChange,
  onActiveIdChange,
  onSelect,
  onBack,
  onKeyDown,
}: {
  menu: Exclude<ThreadComposerControlMenu, null>
  items: SelectableItem[]
  activeId: string
  query: string
  listLabel: string
  backLabel: string | null
  search: { label: string; placeholder: string } | null
  hint: string | null
  currentOf: (itemId: string) => boolean
  listRef: RefObject<HTMLUListElement | null>
  searchRef: RefObject<HTMLInputElement | null>
  onQueryChange: (query: string) => void
  onActiveIdChange: (id: string) => void
  onSelect: (id: string) => void
  onBack: () => void
  onKeyDown: (event: KeyboardEvent<HTMLElement>) => void
}) {
  return (
    <div
      className={[
        'thread-composer-menu',
        menu === 'permission' ? 'permission' : menu === 'environment' ? 'environment' : 'model',
      ].join(' ')}
    >
      {backLabel == null ? null : (
        <button
          type="button"
          className="thread-composer-menu-back"
          onMouseDown={(event) => event.preventDefault()}
          onClick={onBack}
        >
          <ChevronLeft aria-hidden="true" />
          {backLabel}
        </button>
      )}
      {search == null ? null : (
        <input
          ref={searchRef}
          type="search"
          className="thread-composer-menu-search"
          value={query}
          aria-label={search.label}
          placeholder={search.placeholder}
          onChange={(event) => onQueryChange(event.target.value)}
          onKeyDown={onKeyDown}
        />
      )}
      <ul
        ref={listRef}
        role="listbox"
        tabIndex={-1}
        aria-label={listLabel}
        onKeyDown={onKeyDown}
      >
        {items.map((item) => {
          const active = item.id === activeId
          const current = currentOf(item.id)
          return (
            <li key={item.id}>
              <button
                type="button"
                role="option"
                aria-selected={active}
                aria-current={current ? 'true' : undefined}
                className={active ? 'active' : undefined}
                onMouseMove={() => onActiveIdChange(item.id)}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => onSelect(item.id)}
              >
                <span>{item.label}</span>
                {current ? <Check aria-hidden="true" /> : null}
              </button>
            </li>
          )
        })}
      </ul>
      {hint == null ? null : (
        <p className="thread-composer-menu-hint" role="status">{hint}</p>
      )}
    </div>
  )
}
