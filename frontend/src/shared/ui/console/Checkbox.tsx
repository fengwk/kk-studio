import { Check } from 'lucide-react'
import type { ReactNode } from 'react'

export interface CheckboxProps {
  id?: string
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
  label?: ReactNode
  children?: ReactNode
  className?: string
  'aria-label'?: string
  'aria-describedby'?: string
}

/**
 * 共享受控 Checkbox 组件：
 * 采用原生 input[type="checkbox"] 保证可访问性、焦点与键盘操作（如空格键切换），
 * 并使用自定义图标呈现与主题 token 一致的现代外观。
 */
export function Checkbox({
  id,
  checked,
  onChange,
  disabled = false,
  label,
  children,
  className,
  'aria-label': ariaLabel,
  'aria-describedby': ariaDescribedBy,
}: CheckboxProps) {
  const content = label ?? children

  return (
    <label
      className={[
        'ui-checkbox',
        checked ? 'is-checked' : '',
        disabled ? 'is-disabled' : '',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      <input
        id={id}
        type="checkbox"
        className="ui-checkbox-input"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        aria-label={ariaLabel}
        aria-describedby={ariaDescribedBy}
      />
      <span className="ui-checkbox-box" aria-hidden="true">
        {checked && <Check className="ui-checkbox-check" />}
      </span>
      {content ? <span className="ui-checkbox-label">{content}</span> : null}
    </label>
  )
}
