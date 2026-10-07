import type { TextareaHTMLAttributes } from 'react'
import './controls.css'

export interface TextAreaProps
  extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'aria-invalid'> {
  /** 校验失败：同时给出 aria-invalid 与 danger 边框语义。 */
  invalid?: boolean
}

/**
 * 共享多行文本输入：统一主题表面、焦点/错误/禁用语义与纵向缩放。
 * 字数校验、提交与文案仍归调用方。
 */
export function TextArea({ invalid = false, className, ...rest }: TextAreaProps) {
  return (
    <textarea
      className={['ui-textarea', className].filter(Boolean).join(' ')}
      aria-invalid={invalid || undefined}
      data-invalid={invalid || undefined}
      {...rest}
    />
  )
}
