import type { ReactNode } from 'react'
import './controls.css'

/**
 * 表单标签；`required` 时追加红色 *（aria-hidden，语义仍靠控件 required）。
 * 给出 `htmlFor` 时渲染为真正的 `<label>`，字段错误因此可以留在控件的 label 之外；
 * 没有 `htmlFor` 时保持为 `<span>`，供仍然用包裹 label 的表单复用。
 */
export function FieldLabel({
  children,
  required = false,
  htmlFor,
}: {
  children: ReactNode
  required?: boolean
  htmlFor?: string
}) {
  const content = (
    <>
      {children}
      {required ? (
        <span className="field-required" aria-hidden="true">
          {' '}
          *
        </span>
      ) : null}
    </>
  )
  return htmlFor
    ? (
        <label className="field-label" htmlFor={htmlFor}>
          {content}
        </label>
      )
    : <span className="field-label">{content}</span>
}
