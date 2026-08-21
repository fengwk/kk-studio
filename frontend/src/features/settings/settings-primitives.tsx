import { useId, type ReactNode } from 'react'
import { useI18n } from '@/shared/i18n'
import { NumberInput } from '@/shared/ui/console/NumberInput'
import { Select } from '@/shared/ui/console/Select'

/** 配置生效时机：下次调用、新建对话或进程重启。 */
export type ApplyTiming = 'nextInvocation' | 'nextChat' | 'restart'

export function ApplyTimingBadge({ timing }: { timing: ApplyTiming }) {
  const { t } = useI18n()
  return (
    <span className="settings-apply-timing" data-timing={timing}>
      {t({
        nextInvocation: 'settings.applyTiming.nextInvocation',
        nextChat: 'settings.applyTiming.nextChat',
        restart: 'settings.applyTiming.restart',
      }[timing])}
    </span>
  )
}

export function SettingsCard({
  title,
  description,
  timing,
  children,
}: {
  title: string
  description?: string
  timing?: ApplyTiming
  children: ReactNode
}) {
  const titleId = useId()
  return (
    <section className="settings-card" aria-labelledby={titleId}>
      <header className="settings-card-header">
        <div className="settings-card-title-row">
          <h2 id={titleId}>{title}</h2>
          {timing ? <ApplyTimingBadge timing={timing} /> : null}
        </div>
        {description ? <p>{description}</p> : null}
      </header>
      {children}
    </section>
  )
}

export function SettingsSwitchRow({
  label,
  description,
  checked,
  onChange,
  disabled = false,
  ariaLabel,
  fieldPath,
  nullable = false,
}: {
  label: string
  description?: string
  checked: boolean
  onChange: (next: boolean) => void
  disabled?: boolean
  ariaLabel?: string
  fieldPath?: string
  nullable?: boolean
}) {
  return (
    <div
      className="settings-row"
      data-settings-field-path={fieldPath}
      data-settings-nullable={nullable}
    >
      <div className="settings-row-text">
        <strong>{label}</strong>
        {description ? <span className="settings-row-description">{description}</span> : null}
      </div>
      <button
        type="button"
        role="switch"
        className="settings-switch"
        aria-checked={checked}
        aria-label={ariaLabel ?? label}
        disabled={disabled}
        onClick={() => onChange(!checked)}
      >
        <span className="settings-switch-thumb" aria-hidden="true" />
      </button>
    </div>
  )
}

export function SettingsTextField({
  label,
  value,
  onChange,
  placeholder,
  disabled = false,
  type = 'text',
  description,
  hint,
  maxLength,
  fieldPath,
  nullable = false,
}: {
  label: string
  value: string
  onChange: (next: string) => void
  placeholder?: string
  disabled?: boolean
  type?: 'text' | 'password'
  description?: string
  hint?: string
  maxLength?: number
  fieldPath?: string
  nullable?: boolean
}) {
  const fieldId = useId()
  const text = description ?? hint
  return (
    <div
      className="settings-field"
      data-settings-field-path={fieldPath}
      data-settings-nullable={nullable}
    >
      <label className="settings-field-label" htmlFor={fieldId}>
        {label}
      </label>
      {text ? <span className="settings-field-description">{text}</span> : null}
      <input
        id={fieldId}
        className="settings-input"
        type={type}
        value={value}
        placeholder={placeholder}
        maxLength={maxLength}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  )
}

/**
 * 数值输入：draft 中一切数值字段（Long 与 Integer）都保持字符串，输入框直接读写该字符串，
 * 只剥离非数字字符（非有损），避免受控 number input 的失焦/清空竞态。
 */
export function SettingsNumberField({
  label,
  value,
  onChange,
  min,
  max,
  step = 1,
  disabled = false,
  description,
  hint,
  fieldPath,
  nullable = false,
}: {
  label: string
  value: string
  onChange: (next: string) => void
  min?: number
  max?: number
  step?: number
  disabled?: boolean
  description?: string
  hint?: string
  fieldPath?: string
  nullable?: boolean
}) {
  const fieldId = useId()
  const text = description ?? hint
  return (
    <div
      className="settings-field"
      data-settings-field-path={fieldPath}
      data-settings-nullable={nullable}
      data-settings-min={min}
      data-settings-max={max}
    >
      <label className="settings-field-label" htmlFor={fieldId}>
        {label}
      </label>
      {text ? <span className="settings-field-description">{text}</span> : null}
      <NumberInput
        id={fieldId}
        value={value}
        min={min}
        max={max}
        step={step}
        disabled={disabled}
        onChange={onChange}
      />
    </div>
  )
}

export interface SettingsSelectOption {
  value: string
  label: string
}

export function SettingsSelectField({
  label,
  value,
  options,
  onChange,
  disabled = false,
  placeholder,
  description,
  hint,
  fieldPath,
  nullable = false,
}: {
  label: string
  value: string
  options: SettingsSelectOption[]
  onChange: (next: string) => void
  disabled?: boolean
  placeholder?: string
  description?: string
  hint?: string
  fieldPath?: string
  nullable?: boolean
}) {
  const fieldId = useId()
  const text = description ?? hint
  return (
    <div
      className="settings-field"
      data-settings-field-path={fieldPath}
      data-settings-nullable={nullable}
      data-settings-options={options.map((option) => option.value).join(',')}
    >
      <label className="settings-field-label" htmlFor={fieldId}>
        {label}
      </label>
      {text ? <span className="settings-field-description">{text}</span> : null}
      <Select
        id={fieldId}
        value={value}
        options={options}
        disabled={disabled}
        placeholder={placeholder}
        onChange={onChange}
      />
    </div>
  )
}
