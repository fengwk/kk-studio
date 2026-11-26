import { ChevronDown } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import { SelectMenu } from './SelectMenu'
import { useSelectListbox } from './useSelectListbox'
import { useSelectMenuPosition } from './useSelectMenuPosition'
import './Select.css'

export interface SelectOption {
  value: string
  label: string
  /** 触发器上显示的短文案；缺省时沿用 label。选项文案与可访问名始终是 label。 */
  triggerLabel?: string
  disabled?: boolean
}

interface SelectProps {
  id?: string
  value: string
  options: SelectOption[]
  onChange: (value: string) => void
  disabled?: boolean
  required?: boolean
  placeholder?: string
  compact?: boolean
  touch?: boolean
  className?: string
  title?: string
  'aria-label'?: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
  listboxLabel?: string
}

/**
 * 自定义下拉菜单，提供选中状态和键盘导航。
 *
 * <p>本组件只做编排：开关与键盘在 {@link useSelectListbox}，视口定位在
 * {@link useSelectMenuPosition}，菜单本体在 {@link SelectMenu}。
 */
export function Select({
  id,
  value,
  options,
  onChange,
  disabled = false,
  required = false,
  placeholder,
  compact = false,
  touch = false,
  className,
  title,
  'aria-label': ariaLabel,
  'aria-describedby': ariaDescribedBy,
  'aria-invalid': ariaInvalid,
  listboxLabel,
}: SelectProps) {
  const { t } = useI18n()
  const generatedId = useId()
  const listboxId = `ui-select-listbox-${generatedId.replace(/[^a-zA-Z0-9_-]/g, '')}`
  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const [menuRoot, setMenuRoot] = useState<HTMLElement | null>(null)
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([])
  const selectedIndex = Math.max(
    0,
    options.findIndex((option) => option.value === value && !option.disabled),
  )
  const selected = options.find((option) => option.value === value)
  const fullLabel = selected?.label ?? placeholder ?? t('shared.selectPlaceholder')
  const triggerText =
    selected?.triggerLabel ?? fullLabel

  const { open, activeIndex, modalTouchOptions, setActiveIndex, closeListbox, openListbox, selectValue, moveActive } =
    useSelectListbox({
      options,
      selectedIndex,
      triggerRef,
      rootRef,
      menuRef,
      optionRefs,
      onSelectValue: onChange,
    })
  const { menuPosition, placeMenuBeforeOpen } = useSelectMenuPosition({
    open,
    compact,
    triggerRef,
    menuRef,
  })

  /** 打开前先按触发按钮定位，避免菜单首帧出现在未定位的位置。 */
  const handleOpen = () => {
    setMenuRoot(triggerRef.current?.closest<HTMLElement>('.modal-backdrop, dialog:modal') ?? document.body)
    placeMenuBeforeOpen()
    openListbox()
  }

  const handleTriggerKeyDown = (event: React.KeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (!open) {
        handleOpen()
        return
      }
      const option = options[activeIndex]
      if (option && !option.disabled) {
        selectValue(option.value)
      }
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (open) {
        moveActive(event.key === 'ArrowDown' ? 1 : -1)
      } else {
        handleOpen()
      }
    }
  }

  const handleOptionKeyDown = (
    event: React.KeyboardEvent<HTMLButtonElement>,
    option: SelectOption,
  ) => {
    if (event.key === 'Tab') {
      // 同步恢复控件位置，再由浏览器执行原生 Tab/Shift+Tab 默认动作。
      closeListbox(true)
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      moveActive(event.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (!option.disabled) {
        selectValue(option.value)
      }
    }
  }

  return (
    <div
      ref={rootRef}
      className={[
        'ui-select',
        compact ? 'is-compact' : '',
        selected ? '' : 'is-placeholder',
        disabled ? 'is-disabled' : '',
        open ? 'is-open' : '',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      <button
        ref={triggerRef}
        id={id}
        type="button"
        className="ui-select-trigger"
        data-ui-focus="control"
        data-touch={touch || undefined}
        disabled={disabled}
        title={title ?? fullLabel}
        aria-label={ariaLabel}
        aria-describedby={ariaDescribedBy}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listboxId}
        aria-required={required || undefined}
        aria-invalid={ariaInvalid || undefined}
        data-invalid={ariaInvalid || undefined}
        data-value={value}
        onClick={() => (open ? closeListbox() : handleOpen())}
        onKeyDown={handleTriggerKeyDown}
      >
        <span className="ui-select-value">{triggerText}</span>
        <ChevronDown className={`ui-select-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open && menuRoot
        ? (
            <SelectMenu
              portalRoot={menuRoot}
              listboxId={listboxId}
              options={options}
              value={value}
              activeIndex={activeIndex}
              compact={compact}
              modalTouchOptions={modalTouchOptions}
              className={className}
              ariaLabel={listboxLabel ?? ariaLabel}
              menuPosition={menuPosition}
              menuRef={menuRef}
              optionRefs={optionRefs}
              onHoverIndex={setActiveIndex}
              onSelect={(option) => selectValue(option.value)}
              onOptionKeyDown={handleOptionKeyDown}
            />
          )
        : null}
    </div>
  )
}
