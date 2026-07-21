import { ChevronDown } from 'lucide-react'

export interface FormSelectOption {
  value: string
  label: string
  disabled?: boolean
}

export function FormSelect({
  value,
  options,
  onChange,
  disabled = false,
  required = false,
  placeholder = '请选择',
  'aria-label': ariaLabel,
}: {
  value: string
  options: FormSelectOption[]
  onChange: (value: string) => void
  disabled?: boolean
  required?: boolean
  placeholder?: string
  'aria-label'?: string
}) {
  const selected = options.some((option) => option.value === value)
  // 仅渲染一个 labelable 表单控件（原生 <select>），外部 <label> 包裹的重复关联问题由此收敛。
  return (
    <div
      className={`form-select${selected ? '' : ' is-placeholder'}${disabled ? ' is-disabled' : ''}`}
    >
      <select
        className="form-select-control"
        value={value}
        required={required}
        disabled={disabled}
        aria-label={ariaLabel}
        onChange={(event) => onChange(event.target.value)}
      >
        {!selected && placeholder ? (
          <option value="" disabled hidden>
            {placeholder}
          </option>
        ) : null}
        {options.map((option) => (
          <option key={option.value} value={option.value} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
      </select>
      <ChevronDown className="form-select-chevron" aria-hidden="true" />
    </div>
  )
}
