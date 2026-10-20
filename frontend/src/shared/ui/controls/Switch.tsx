import './controls.css'

export interface SwitchProps {
  checked: boolean
  onChange: (next: boolean) => void
  /** 开关没有可见文本，必须提供无障碍名称。 */
  label: string
  disabled?: boolean
}

/** 共享开关：原生 button + role="switch"，Space/Enter 切换，焦点环由全局规则提供。 */
export function Switch({ checked, onChange, label, disabled }: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      className="ui-switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <span className="ui-switch-thumb" aria-hidden="true" />
    </button>
  )
}
