import type { InputHTMLAttributes } from 'react'
import './controls.css'

export interface TextInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'aria-invalid'> {
  /** 校验失败：同时给出 aria-invalid 与 danger 边框语义。 */
  invalid?: boolean
}

/**
 * 共享单行文本输入：统一 32px 高度、主题表面与焦点/错误/禁用语义。
 * 表单校验、请求与文案仍归调用方，此处只负责控件外观与可访问性。
 */
export function TextInput({ invalid = false, className, type = 'text', ...rest }: TextInputProps) {
  return (
    <input
      type={type}
      className={['ui-text-input', className].filter(Boolean).join(' ')}
      aria-invalid={invalid || undefined}
      data-invalid={invalid || undefined}
      {...rest}
    />
  )
}
