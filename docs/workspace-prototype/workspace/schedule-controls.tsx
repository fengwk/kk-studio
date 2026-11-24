import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { HelpHint } from '@/shared/ui/controls/HelpHint'
import { DateInput } from './DateInput'
import { TimeInput } from './TimeInput'

type NativeControlProps = {
  nativeId: string
  placeholder?: string
  min?: boolean
  component: 'date' | 'time'
}

function NativeControl({ nativeId, placeholder, min, component }: NativeControlProps) {
  const input = document.getElementById(nativeId) as HTMLInputElement
  const [value, setValue] = useState(input.value)
  const [disabled, setDisabled] = useState(input.disabled)
  const [invalid, setInvalid] = useState(input.getAttribute('aria-invalid') === 'true')
  const [minimum, setMinimum] = useState(input.min)
  const [maximum, setMaximum] = useState(input.max)
  const triggerId = `${nativeId}-picker`

  useEffect(() => {
    input.tabIndex = -1
    input.setAttribute('aria-hidden', 'true')
    const sync = () => {
      setValue(input.value)
      setDisabled(input.disabled)
      setInvalid(!input.validity.valid && input.getAttribute('aria-invalid') === 'true')
      setMinimum(input.min)
      setMaximum(input.max)
    }
    const focusTrigger = () => document.getElementById(triggerId)?.focus()
    const markInvalid = () => setInvalid(true)
    input.addEventListener('input', sync)
    input.addEventListener('change', sync)
    input.addEventListener('invalid', markInvalid)
    input.addEventListener('focus', focusTrigger)
    return () => {
      input.removeEventListener('input', sync)
      input.removeEventListener('change', sync)
      input.removeEventListener('invalid', markInvalid)
      input.removeEventListener('focus', focusTrigger)
    }
  }, [input, triggerId])

  const change = (next: string) => {
    input.value = next
    setValue(next)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
  }
  const commonProps = {
    id: triggerId,
    value,
    onChange: change,
    placeholder,
    disabled,
    required: input.required,
    'aria-invalid': invalid || undefined,
    'aria-describedby': input.getAttribute('aria-describedby') || undefined,
  }
  return component === 'date'
    ? <DateInput {...commonProps} min={min ? minimum : undefined} max={min ? maximum : undefined} />
    : <TimeInput {...commonProps} />
}

export function ScheduleControls() {
  const dateHost = document.getElementById('sch-date-control')
  const repeatTimeHost = document.getElementById('sch-time-control')
  const onceTimeHost = document.getElementById('sch-once-time-control')
  const threadHelpHost = document.getElementById('sch-thread-help')
  const cronHelpHost = document.getElementById('sch-cron-help')
  return <>
    {dateHost && createPortal(<NativeControl nativeId="sch-date" component="date" placeholder="选择日期" min />, dateHost)}
    {repeatTimeHost && createPortal(<NativeControl nativeId="sch-time" component="time" placeholder="选择时间" />, repeatTimeHost)}
    {onceTimeHost && createPortal(<NativeControl nativeId="sch-once-time" component="time" placeholder="选择时间" />, onceTimeHost)}
    {threadHelpHost && createPortal(<HelpHint label="帮助：Thread">Thread 未空闲时，本次跳过。</HelpHint>, threadHelpHost)}
    {cronHelpHost && createPortal(<HelpHint label="帮助：cron 表达式">依次填写分钟、小时、日期、月份、星期。</HelpHint>, cronHelpHost)}
  </>
}
