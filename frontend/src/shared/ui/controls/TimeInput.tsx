import { useI18n } from '@/shared/i18n'
import './Select.css'
import { useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { Clock } from 'lucide-react'
import { usePickerOverlay } from './usePickerOverlay'
import './TimeInput.css'

type TimeInputProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'value' | 'onChange'> & {
  id?: string
  value: string
  onChange: (value: string) => void
  placeholder?: string
  disabled?: boolean
  required?: boolean
  allow24?: boolean
}

type TimeChoice = { hour: number; minute: number }
const pad = (value: number) => String(value).padStart(2, '0')
const parseTime = (value: string): TimeChoice => {
  const match = /^(\d{2}):(\d{2})$/.exec(value)
  if (match && Number(match[1]) <= 23 && Number(match[2]) <= 59) return { hour: Number(match[1]), minute: Number(match[2]) }
  if (value === '24:00') return { hour: 24, minute: 0 }
  return { hour: 0, minute: 0 }
}

export function TimeInput({ id, value, onChange, placeholder, disabled, required, allow24, className, onKeyDown, onClick: onTriggerClick, ...buttonProps }: TimeInputProps) {
  const { t } = useI18n()
  const reactId = useId().replace(/:/g, '')
  const triggerId = id || `ui-time-${reactId}`
  const panelId = `${triggerId}-panel`
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [choice, setChoice] = useState<TimeChoice>(() => parseTime(value))
  const [column, setColumn] = useState<'hour' | 'minute'>('hour')
  const portalRoot = usePickerOverlay(open, setOpen, triggerRef, panelRef)
  useEffect(() => {
    if (!open) return
    for (const [kind, number] of [['hour', choice.hour], ['minute', choice.minute]] as const) {
      const option = document.getElementById(`${panelId}-${kind}-${pad(number)}`)
      const list = option?.parentElement
      if (option && list) list.scrollTop += option.getBoundingClientRect().top - list.getBoundingClientRect().top - (list.clientHeight - option.offsetHeight) / 2
    }
  }, [choice.hour, choice.minute, open, panelId, portalRoot])
  const hourCount = allow24 ? 25 : 24
  const commit = (next: TimeChoice) => {
    if (next.hour === 24 && next.minute !== 0) return
    setOpen(false)
    const result = `${pad(next.hour)}:${pad(next.minute)}`
    if (result !== value) onChange(result)
    triggerRef.current?.focus()
  }
  const selectHour = (hour: number) => {
    const minute = hour === 24 ? 0 : parseTime(value).minute
    setChoice({ hour, minute })
    setColumn('minute')
    const result = `${pad(hour)}:${pad(minute)}`
    if (result !== value) onChange(result)
  }
  const begin = () => {
    if (disabled) return
    setChoice(parseTime(value))
    setColumn('hour')
    setOpen(true)
  }
  const updateActive = (next: number) => setChoice((current) => column === 'hour'
    ? { hour: (next + hourCount) % hourCount, minute: (next + hourCount) % hourCount === 24 ? 0 : current.minute }
    : current.hour === 24 ? current : { ...current, minute: (next + 60) % 60 })
  const keyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    onKeyDown?.(event)
    if (event.defaultPrevented) return
    if (!open) {
      if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
        event.preventDefault()
        begin()
      }
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      setOpen(false)
      triggerRef.current?.focus()
    } else if (event.key === 'Tab') setOpen(false)
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault()
      setColumn(event.key === 'ArrowLeft' ? 'hour' : 'minute')
    } else if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      event.preventDefault()
      const current = column === 'hour' ? choice.hour : choice.minute
      updateActive(current + (event.key === 'ArrowUp' ? -1 : 1))
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      updateActive(event.key === 'Home' ? 0 : column === 'hour' ? hourCount - 1 : 59)
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (column === 'hour') selectHour(choice.hour)
      else commit(choice)
    }
  }

  const activeId = `${panelId}-${column}-${pad(column === 'hour' ? choice.hour : choice.minute)}`
  const panel = open && portalRoot && <div ref={panelRef} id={panelId} className="ui-time-panel" role="dialog" aria-label={t('controls.chooseTime')} onKeyDown={(event) => { if (event.key === 'Tab') setOpen(false) }}>
    <div className="ui-time-columns">
      <div className="ui-time-column" role="listbox" aria-label={t('controls.hour')} aria-activedescendant={`${panelId}-hour-${pad(choice.hour)}`}>
        {Array.from({ length: hourCount }, (_, hour) => <div key={hour} id={`${panelId}-hour-${pad(hour)}`} role="option" aria-selected={!!value && parseTime(value).hour === hour} className={`ui-time-option${column === 'hour' && choice.hour === hour ? ' is-active' : ''}`} tabIndex={-1} onMouseDown={(event) => event.preventDefault()} onClick={() => {
          selectHour(hour)
        }}>{pad(hour)}</div>)}
      </div>
      <div className="ui-time-separator" aria-hidden="true">:</div>
      <div className="ui-time-column" role="listbox" aria-label={t('controls.minute')} aria-activedescendant={`${panelId}-minute-${pad(choice.minute)}`}>
        {Array.from({ length: 60 }, (_, minute) => <div key={minute} id={`${panelId}-minute-${pad(minute)}`} role="option" aria-selected={!!value && parseTime(value).minute === minute} aria-disabled={choice.hour === 24 && minute !== 0 || undefined} className={`ui-time-option${column === 'minute' && choice.minute === minute ? ' is-active' : ''}`} tabIndex={-1} onMouseDown={(event) => event.preventDefault()} onClick={() => { if (choice.hour !== 24 || minute === 0) commit({ hour: choice.hour, minute }) }}>{pad(minute)}</div>)}
      </div>
    </div>
  </div>
  const valid = /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value) || (allow24 && value === '24:00')
  const label = valid ? value : placeholder ?? t('controls.chooseTime')

  return <>
    <button {...buttonProps} ref={triggerRef} id={triggerId} value={value} type={buttonProps.type || 'button'} disabled={disabled} className={`ui-select-trigger${!valid ? ' is-placeholder' : ''}${className ? ` ${className}` : ''}`} aria-haspopup="dialog" aria-expanded={open} aria-controls={panelId} aria-activedescendant={open ? activeId : undefined} aria-required={required || buttonProps['aria-required'] || undefined} onClick={(event) => { onTriggerClick?.(event); if (!event.defaultPrevented) { if (open) setOpen(false); else begin() } }} onKeyDown={keyDown}>
      <span className="ui-select-value">{label}</span><Clock className="ui-select-chevron" aria-hidden="true" />
    </button>
    {panel && portalRoot && createPortal(panel, portalRoot)}
  </>
}
