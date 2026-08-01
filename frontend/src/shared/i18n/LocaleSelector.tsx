import { Check, ChevronDown } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { useI18n, type AppLocale } from '@/shared/i18n'

const localeOptions: Array<{ value: AppLocale; label: string }> = [
  { value: 'en-US', label: 'English' },
  { value: 'zh-CN', label: '中文' },
]

export function LocaleSelector({ className }: { className?: string }) {
  const { locale, setLocale, t } = useI18n()
  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([])
  const [open, setOpen] = useState(false)
  const selectedIndex = Math.max(0, localeOptions.findIndex((option) => option.value === locale))
  const [activeIndex, setActiveIndex] = useState(selectedIndex)
  const instanceId = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  const listboxId = `locale-selector-listbox-${instanceId}`

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

  function selectLocale(nextLocale: AppLocale) {
    setLocale(nextLocale)
    closeListbox(true)
  }

  function moveActive(delta: number) {
    const nextIndex = (activeIndex + delta + localeOptions.length) % localeOptions.length
    setActiveIndex(nextIndex)
  }

  function moveActiveTo(index: number) {
    setActiveIndex(index)
  }

  useEffect(() => {
    if (!open) {
      return
    }
    optionRefs.current[activeIndex]?.focus()
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
        selectLocale(localeOptions[activeIndex].value)
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
      return
    }
    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      if (open) {
        moveActiveTo(event.key === 'Home' ? 0 : localeOptions.length - 1)
      } else {
        openListbox()
      }
    }
  }

  function handleOptionKeyDown(
    event: React.KeyboardEvent<HTMLButtonElement>,
    option: (typeof localeOptions)[number],
  ) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      moveActive(event.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      moveActiveTo(event.key === 'Home' ? 0 : localeOptions.length - 1)
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      selectLocale(option.value)
    }
  }

  return (
    <div
      ref={rootRef}
      className={['locale-selector', className].filter(Boolean).join(' ')}
    >
      <button
        ref={triggerRef}
        type="button"
        className="locale-selector-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listboxId}
        onClick={() => (open ? closeListbox() : openListbox())}
        onKeyDown={handleTriggerKeyDown}
      >
        <span className="sr-only">
          {t('platform.localeSelector')}: {localeOptions[selectedIndex].label}
        </span>
        <span className="locale-selector-current" aria-hidden="true">
          {localeOptions[selectedIndex].label}
        </span>
        <ChevronDown
          className={`locale-selector-chevron${open ? ' is-open' : ''}`}
          aria-hidden="true"
        />
      </button>
      {open ? (
        <div
          id={listboxId}
          className="locale-selector-menu"
          role="listbox"
          aria-label={t('platform.localeSelector')}
        >
          {localeOptions.map((option, index) => {
            const selected = locale === option.value
            return (
              <button
                key={option.value}
                id={`${listboxId}-${option.value}`}
                ref={(element) => {
                  optionRefs.current[index] = element
                }}
                type="button"
                role="option"
                tabIndex={activeIndex === index ? 0 : -1}
                aria-selected={selected}
                className={`locale-selector-option${activeIndex === index ? ' is-active' : ''}`}
                onClick={() => selectLocale(option.value)}
                onKeyDown={(event) => handleOptionKeyDown(event, option)}
                onMouseEnter={() => setActiveIndex(index)}
              >
                <span className="locale-option-label">{option.label}</span>
                <span className="locale-option-indicator" aria-hidden="true">
                  {selected ? <Check /> : null}
                </span>
              </button>
            )
          })}
        </div>
      ) : null}
    </div>
  )
}
