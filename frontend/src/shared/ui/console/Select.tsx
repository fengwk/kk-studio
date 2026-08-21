import { Check, ChevronDown } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { useI18n } from '@/shared/i18n'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
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
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([])
  const selectedIndex = Math.max(
    0,
    options.findIndex((option) => option.value === value && !option.disabled),
  )
  const [open, setOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(selectedIndex)
  const selected = options.find((option) => option.value === value)
  const effectivePlaceholder = placeholder ?? t('shared.selectPlaceholder')
  const triggerLabel = selected?.label ?? effectivePlaceholder

  function closeListbox(focusTrigger = false) {
    setOpen(false)
    if (focusTrigger) {
      triggerRef.current?.focus()
    }
  }

  function openListbox() {
    setActiveIndex(selectedIndex)
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

  useEffect(() => {
    if (!open) {
      return
    }
    function handlePointerDown(event: PointerEvent) {
      const target = event.target
      if (!(target instanceof Node) || !rootRef.current?.contains(target)) {
        closeListbox()
      }
    }
    function handleFocusIn(event: FocusEvent) {
      const target = event.target
      if (!(target instanceof Node) || !rootRef.current?.contains(target)) {
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
      {open ? (
        <div id={listboxId} className="ui-select-menu" role="listbox" aria-label={listboxLabel ?? ariaLabel}>
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
        </div>
      ) : null}
    </div>
  )
}
