import { Check, Minus } from 'lucide-react'
import type { ReactNode } from 'react'
import './controls.css'

export interface CheckboxProps {
  id?: string
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
  /** 部分选中（例如“种类全选”下的部分条目已勾选）。 */
  indeterminate?: boolean
  /** 校验失败：给出 aria-invalid 与 danger 边框，与其它共享表单控件一致。 */
  invalid?: boolean
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
  indeterminate = false,
  invalid = false,
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
        !checked && indeterminate ? 'is-indeterminate' : '',
        invalid ? 'is-invalid' : '',
        disabled ? 'is-disabled' : '',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      <input
        id={id}
        ref={(element) => {
          // 原生 indeterminate 只能通过 DOM 属性表达。
          if (element) {
            element.indeterminate = indeterminate && !checked
          }
        }}
        type="checkbox"
        className="ui-checkbox-input"
        checked={checked}
        aria-checked={indeterminate && !checked ? 'mixed' : checked}
        aria-invalid={invalid || undefined}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        aria-label={ariaLabel}
        aria-describedby={ariaDescribedBy}
      />
      <span className="ui-checkbox-box" aria-hidden="true">
        {checked ? <Check className="ui-checkbox-check" /> : null}
        {!checked && indeterminate ? <Minus className="ui-checkbox-check" /> : null}
      </span>
      {content ? <span className="ui-checkbox-label">{content}</span> : null}
    </label>
  )
}
