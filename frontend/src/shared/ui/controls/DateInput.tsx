import { useI18n } from '@/shared/i18n'
import './Select.css'
import { useId, useRef, useState, type ButtonHTMLAttributes, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { Calendar, ChevronLeft, ChevronRight } from 'lucide-react'
import { usePickerOverlay } from './usePickerOverlay'
import './DateInput.css'

type DateInputProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'value' | 'onChange'> & {
  id?: string
  value: string
  onChange: (value: string) => void
  placeholder?: string
  disabled?: boolean
  required?: boolean
  min?: string
  max?: string
}

const toDate = (value: string) => {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return null
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return date.getFullYear() === Number(match[1]) && date.getMonth() === Number(match[2]) - 1 && date.getDate() === Number(match[3]) ? date : null
}
const isoDate = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
const dateAt = (year: number, month: number, day: number) => new Date(year, month, day)
const addDays = (date: Date, amount: number) => dateAt(date.getFullYear(), date.getMonth(), date.getDate() + amount)
const addMonths = (date: Date, amount: number) => dateAt(date.getFullYear(), date.getMonth() + amount, Math.min(date.getDate(), new Date(date.getFullYear(), date.getMonth() + amount + 1, 0).getDate()))
const formatDate = (date: Date, options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(document.documentElement.lang || undefined, options).format(date)

export function DateInput({ id, value, onChange, placeholder, disabled, required, min, max, className, onKeyDown, onClick: onTriggerClick, ...buttonProps }: DateInputProps) {
  const { t } = useI18n()
  const reactId = useId().replace(/:/g, '')
  const triggerId = id || `ui-date-${reactId}`
  const panelId = `${triggerId}-panel`
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState<Date>(() => toDate(value) || new Date())
  const portalRoot = usePickerOverlay(open, setOpen, triggerRef, panelRef)
  const minDate = toDate(min || '')
  const maxDate = toDate(max || '')
  const blocked = (date: Date) => !!((min && isoDate(date) < min) || (max && isoDate(date) > max))
  const begin = () => {
    if (disabled) return
    const current = toDate(value) || new Date()
    const next = minDate && current < minDate ? minDate : maxDate && current > maxDate ? maxDate : current
    setActive(next)
    setOpen(true)
  }
  const choose = (date: Date | null) => {
    if (date && blocked(date)) return
    setOpen(false)
    if ((date ? isoDate(date) : '') !== value) onChange(date ? isoDate(date) : '')
    triggerRef.current?.focus()
  }
  const move = (date: Date) => setActive(dateAt(date.getFullYear(), date.getMonth(), date.getDate()))
  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    onKeyDown?.(event)
    if (event.defaultPrevented) return
    if (!open) {
      if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
        event.preventDefault()
        begin()
      }
      return
    }
    const delta: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }
    if (delta[event.key] !== undefined) {
      event.preventDefault()
      move(addDays(active, delta[event.key]))
    } else if (event.key === 'PageUp' || event.key === 'PageDown') {
      event.preventDefault()
      move(addMonths(active, (event.key === 'PageUp' ? -1 : 1) * (event.shiftKey ? 12 : 1)))
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      const offset = (active.getDay() + 6) % 7
      move(addDays(active, event.key === 'Home' ? -offset : 6 - offset))
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      choose(active)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      setOpen(false)
      triggerRef.current?.focus()
    } else if (event.key === 'Tab') setOpen(false)
  }

  const monthStart = dateAt(active.getFullYear(), active.getMonth(), 1)
  const gridStart = addDays(monthStart, -((monthStart.getDay() + 6) % 7))
  const today = isoDate(new Date())
  const weekdays = Array.from({ length: 7 }, (_, index) => formatDate(addDays(dateAt(2024, 0, 1), index), { weekday: 'short' }))
  const days = Array.from({ length: 42 }, (_, index) => addDays(gridStart, index))
  const displayValue = toDate(value)
  const label = displayValue ? formatDate(displayValue, { year: 'numeric', month: 'short', day: 'numeric' }) : placeholder ?? t('controls.chooseDate')
  const panel = open && portalRoot && <div ref={panelRef} id={panelId} className="ui-date-panel" role="dialog" aria-label={t('controls.chooseDate')} onKeyDown={(event) => { if (event.key === 'Tab') setOpen(false) }}>
    <div className="ui-date-head">
      <button className="ui-date-nav" type="button" aria-label={t('controls.previousMonth')} tabIndex={-1} onMouseDown={(event) => event.preventDefault()} onClick={() => move(addMonths(active, -1))}><ChevronLeft aria-hidden="true" /></button>
      <div className="ui-date-title" aria-live="polite">{formatDate(monthStart, { year: 'numeric', month: 'long' })}</div>
      <button className="ui-date-nav" type="button" aria-label={t('controls.nextMonth')} tabIndex={-1} onMouseDown={(event) => event.preventDefault()} onClick={() => move(addMonths(active, 1))}><ChevronRight aria-hidden="true" /></button>
    </div>
    <div className="ui-date-grid" role="grid" aria-label={formatDate(monthStart, { year: 'numeric', month: 'long' })}>
      <div className="ui-date-row" role="row">{weekdays.map((day, index) => <div key={index} className="ui-date-week" role="columnheader">{day}</div>)}</div>
      {Array.from({ length: 6 }, (_, row) => <div className="ui-date-row" role="row" key={row}>{days.slice(row * 7, row * 7 + 7).map((date) => {
        const iso = isoDate(date)
        const isBlocked = blocked(date)
        return <div key={iso} id={`${panelId}-${iso}`} className={`ui-date-day${date.getMonth() !== active.getMonth() ? ' is-outside' : ''}${iso === today ? ' is-today' : ''}${iso === isoDate(active) ? ' is-active' : ''}`} role="gridcell" aria-selected={iso === value} aria-disabled={isBlocked || undefined} aria-label={formatDate(date, { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })} tabIndex={-1} onMouseDown={(event) => event.preventDefault()} onClick={() => choose(date)}>{date.getDate()}</div>
      })}</div>)}
    </div>
    <div className="ui-date-foot"><button type="button" className="ui-date-link" disabled={blocked(new Date())} tabIndex={-1} onMouseDown={(event) => event.preventDefault()} onClick={() => choose(new Date())}>{t('controls.today')}</button>{!required && <button type="button" className="ui-date-link" tabIndex={-1} onMouseDown={(event) => event.preventDefault()} onClick={() => choose(null)}>{t('controls.clear')}</button>}</div>
  </div>

  return <>
    <button {...buttonProps} ref={triggerRef} id={triggerId} value={value} type={buttonProps.type || 'button'} disabled={disabled} className={`ui-select-trigger${!displayValue ? ' is-placeholder' : ''}${className ? ` ${className}` : ''}`} data-ui-focus="control" aria-haspopup="dialog" aria-expanded={open} aria-controls={panelId} aria-activedescendant={open ? `${panelId}-${isoDate(active)}` : undefined} aria-required={required || undefined} onClick={(event) => { onTriggerClick?.(event); if (!event.defaultPrevented) { if (open) setOpen(false); else begin() } }} onKeyDown={handleKeyDown}>
      <span className="ui-select-value">{label}</span><Calendar className="ui-select-chevron" aria-hidden="true" />
    </button>
    {panel && portalRoot && createPortal(panel, portalRoot)}
  </>
}
