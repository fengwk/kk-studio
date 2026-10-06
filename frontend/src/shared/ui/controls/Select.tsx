import { Check, ChevronDown } from 'lucide-react'
import { useEffect, useLayoutEffect, useId, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { useI18n } from '@/shared/i18n'
import './controls.css'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

const MENU_MARGIN = 8
const MENU_GAP = 6
const MENU_MIN_HEIGHT = 120
const MENU_MAX_HEIGHT = 280
const MENU_MAX_WIDTH = 360

/**
 * 菜单位于 body portal，必须用视口坐标定位，避免被滚动容器（如 modal-body）裁剪。
 * 触发按钮与菜单都不在彼此的可滚动祖先内，故外点判断需同时检查两棵子树。
 */
function computeMenuPosition(trigger: HTMLElement, compact: boolean, menu?: HTMLElement | null): CSSProperties {
  const rect = trigger.getBoundingClientRect()
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight
  if (compact) {
    const width = menu?.getBoundingClientRect().width ?? Math.max(rect.width, 148)
    const below = viewportHeight - rect.bottom - MENU_GAP - MENU_MARGIN
    const above = rect.top - MENU_GAP - MENU_MARGIN
    const openBelow = below >= above
    return {
      position: 'fixed',
      ...(openBelow
        ? { top: rect.bottom + MENU_GAP }
        : { bottom: viewportHeight - rect.top + MENU_GAP }),
      left: 'auto',
      right: Math.min(
        Math.max(MENU_MARGIN, viewportWidth - rect.right),
        Math.max(MENU_MARGIN, viewportWidth - MENU_MARGIN - width),
      ),
      '--ui-select-available-height': `${Math.max(0, openBelow ? below : above)}px`,
      '--ui-select-trigger-width': `${rect.width}px`,
    } as CSSProperties & {
      '--ui-select-trigger-width': string
      '--ui-select-available-height': string
    }
  }
  const spaceBelow = viewportHeight - rect.bottom - MENU_GAP - MENU_MARGIN
  const spaceAbove = rect.top - MENU_GAP - MENU_MARGIN
  const openBelow = spaceBelow >= spaceAbove || spaceBelow >= MENU_MIN_HEIGHT
  const vertical: CSSProperties = openBelow
    ? { top: rect.bottom + MENU_GAP }
    : { bottom: viewportHeight - rect.top + MENU_GAP }
  const maxHeight = Math.max(
    0,
    Math.min(MENU_MAX_HEIGHT, openBelow ? spaceBelow : spaceAbove),
  )
  const maxWidth = Math.max(rect.width, Math.min(MENU_MAX_WIDTH, viewportWidth - MENU_MARGIN * 2))
  const width = menu?.getBoundingClientRect().width ?? rect.width
  const maxLeft = Math.max(MENU_MARGIN, viewportWidth - MENU_MARGIN - width)
  return {
    ...vertical,
    position: 'fixed',
    left: Math.min(Math.max(rect.left, MENU_MARGIN), maxLeft),
    right: 'auto',
    minWidth: rect.width,
    maxWidth,
    maxHeight,
  }
}

/**
 * 自定义下拉，视觉与交互对齐 LocaleSelector：圆角菜单、选中勾、键盘导航。
 * 不使用操作系统原生 option 菜单。
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
  className,
  'aria-label': ariaLabel,
  'aria-describedby': ariaDescribedBy,
  'aria-invalid': ariaInvalid,
  listboxLabel,
}: {
  id?: string
  value: string
  options: SelectOption[]
  onChange: (value: string) => void
  disabled?: boolean
  required?: boolean
  placeholder?: string
  compact?: boolean
  className?: string
  'aria-label'?: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
  listboxLabel?: string
}) {
  const { t } = useI18n()
  const generatedId = useId()
  const instanceId = generatedId.replace(/[^a-zA-Z0-9_-]/g, '')
  const listboxId = `ui-select-listbox-${instanceId}`
  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([])
  const selectedIndex = Math.max(
    0,
    options.findIndex((option) => option.value === value && !option.disabled),
  )
  const [open, setOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(selectedIndex)
  const [menuPosition, setMenuPosition] = useState<CSSProperties | null>(null)
  const selected = options.find((option) => option.value === value)
  const effectivePlaceholder = placeholder ?? t('shared.selectPlaceholder')
  const triggerLabel = selected?.label ?? effectivePlaceholder

  /** 触发按钮与 portal 菜单分处两棵子树，判断“内部”必须同时覆盖两者。 */
  function containsNode(node: Node | null): boolean {
    if (node == null) {
      return false
    }
    return rootRef.current?.contains(node) === true || menuRef.current?.contains(node) === true
  }

  function closeListbox(focusTrigger = false) {
    setOpen(false)
    if (focusTrigger) {
      triggerRef.current?.focus()
    }
  }

  function openListbox() {
    setActiveIndex(selectedIndex)
    if (triggerRef.current) {
      setMenuPosition(computeMenuPosition(triggerRef.current, compact))
    }
    setOpen(true)
  }

  function selectValue(next: string) {
    onChange(next)
    closeListbox(true)
  }

  function moveActive(delta: number) {
    if (options.length === 0) {
      return
    }
    let nextIndex = activeIndex
    for (let i = 0; i < options.length; i += 1) {
      nextIndex = (nextIndex + delta + options.length) % options.length
      if (!options[nextIndex]?.disabled) {
        setActiveIndex(nextIndex)
        return
      }
    }
  }

  useEffect(() => {
    if (!open) {
      return
    }
    const option = optionRefs.current[activeIndex]
    option?.focus({ preventScroll: true })
    option?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex, open])

  useLayoutEffect(() => {
    if (!open) {
      return
    }
    function reposition() {
      if (triggerRef.current) {
        setMenuPosition(computeMenuPosition(triggerRef.current, compact, menuRef.current))
      }
    }
    reposition()
    window.addEventListener('resize', reposition)
    // capture 阶段监听任意滚动容器，保证滚动后菜单仍贴着触发按钮。
    window.addEventListener('scroll', reposition, true)
    return () => {
      window.removeEventListener('resize', reposition)
      window.removeEventListener('scroll', reposition, true)
    }
  }, [open, compact])

  useEffect(() => {
    if (!open) {
      return
    }
    function handlePointerDown(event: PointerEvent) {
      const target = event.target
      if (!(target instanceof Node) || !containsNode(target)) {
        closeListbox()
      }
    }
    function handleFocusIn(event: FocusEvent) {
      const target = event.target
      if (!(target instanceof Node) || !containsNode(target)) {
        closeListbox()
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        closeListbox(true)
      }
    }
    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('focusin', handleFocusIn)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('focusin', handleFocusIn)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open])

  function handleTriggerKeyDown(event: React.KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (open) {
        const option = options[activeIndex]
        if (option && !option.disabled) {
          selectValue(option.value)
        }
      } else {
        openListbox()
      }
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (open) {
        moveActive(event.key === 'ArrowDown' ? 1 : -1)
      } else {
        openListbox()
      }
    }
  }

  function handleOptionKeyDown(
    event: React.KeyboardEvent<HTMLButtonElement>,
    option: SelectOption,
  ) {
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
        disabled={disabled}
        aria-label={ariaLabel}
        aria-describedby={ariaDescribedBy}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listboxId}
        aria-required={required || undefined}
        aria-invalid={ariaInvalid || undefined}
        data-invalid={ariaInvalid || undefined}
        data-value={value}
        onClick={() => (open ? closeListbox() : openListbox())}
        onKeyDown={handleTriggerKeyDown}
      >
        <span className="ui-select-value">{triggerLabel}</span>
        <ChevronDown className={`ui-select-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open
        ? createPortal(
            <div
              ref={menuRef}
              id={listboxId}
              className={['ui-select-menu', compact ? 'is-compact' : '', className]
                .filter(Boolean)
                .join(' ')}
              role="listbox"
              aria-label={listboxLabel ?? ariaLabel}
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
                    tabIndex={activeIndex === index ? 0 : -1}
                    disabled={option.disabled}
                    aria-selected={isSelected}
                    aria-disabled={option.disabled || undefined}
                    data-value={option.value}
                    className={`ui-select-option${activeIndex === index ? ' is-active' : ''}`}
                    onClick={() => {
                      if (!option.disabled) {
                        selectValue(option.value)
                      }
                    }}
                    onKeyDown={(event) => handleOptionKeyDown(event, option)}
                    onMouseEnter={() => setActiveIndex(index)}
                  >
                    <span className="ui-select-option-label">{option.label}</span>
                    <span className="ui-select-option-indicator" aria-hidden="true">
                      {isSelected ? <Check /> : null}
                    </span>
                  </button>
                )
              })}
            </div>,
            document.body,
          )
        : null}
    </div>
  )
}
