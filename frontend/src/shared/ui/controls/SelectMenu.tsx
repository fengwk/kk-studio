import { Check } from 'lucide-react'
import { createPortal } from 'react-dom'
import type { CSSProperties, RefObject } from 'react'
import type { SelectOption } from './Select'
import './SelectMenu.css'

interface SelectMenuProps {
  portalRoot: HTMLElement
  listboxId: string
  options: SelectOption[]
  value: string
  activeIndex: number
  compact: boolean
  modalTouchOptions: boolean
  className?: string
  ariaLabel?: string
  menuPosition: CSSProperties | null
  menuRef: RefObject<HTMLDivElement | null>
  optionRefs: RefObject<Array<HTMLButtonElement | null>>
  onHoverIndex: (index: number) => void
  onSelect: (option: SelectOption) => void
  onOptionKeyDown: (event: React.KeyboardEvent<HTMLButtonElement>, option: SelectOption) => void
}

/**
 * 下拉菜单本体：portal 到触发器所属弹层或 body，避免被滚动容器裁剪。
 *
 * <p>选项用 roving tabindex：只有 active 项可 Tab 进入，方向键在选项间移动。
 */
export function SelectMenu({
  portalRoot,
  listboxId,
  options,
  value,
  activeIndex,
  compact,
  modalTouchOptions,
  className,
  ariaLabel,
  menuPosition,
  menuRef,
  optionRefs,
  onHoverIndex,
  onSelect,
  onOptionKeyDown,
}: SelectMenuProps) {
  return createPortal(
    <div
      ref={menuRef}
      id={listboxId}
      className={[
        'ui-select-menu',
        compact ? 'is-compact' : '',
        modalTouchOptions ? 'modal-touch-options' : '',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      role="listbox"
      aria-label={ariaLabel}
      style={menuPosition ?? { position: 'fixed', visibility: 'hidden' }}
    >
      {options.map((option, index) => {
        const isSelected = option.value === value
        return (
          <button
            key={option.value === '' ? '__empty__' : option.value}
            ref={(element) => {
              optionRefs.current[index] = element
            }}
            type="button"
            role="option"
            data-ui-focus="row"
            tabIndex={activeIndex === index ? 0 : -1}
            disabled={option.disabled}
            aria-selected={isSelected}
            aria-disabled={option.disabled || undefined}
            data-value={option.value}
            className={`ui-select-option${activeIndex === index ? ' is-active' : ''}`}
            onClick={() => {
              if (!option.disabled) {
                onSelect(option)
              }
            }}
            onKeyDown={(event) => onOptionKeyDown(event, option)}
            onMouseEnter={() => onHoverIndex(index)}
          >
            <span className="ui-select-option-label">{option.label}</span>
            <span className="ui-select-option-indicator" aria-hidden="true">
              {isSelected ? <Check /> : null}
            </span>
          </button>
        )
      })}
    </div>,
    portalRoot,
  )
}
