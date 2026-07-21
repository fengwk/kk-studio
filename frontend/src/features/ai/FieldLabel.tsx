import type { ReactNode } from 'react'

/** 表单标签；`required` 时追加红色 *（aria-hidden，语义仍靠控件 required）。 */
export function FieldLabel({
  children,
  required = false,
}: {
  children: ReactNode
  required?: boolean
}) {
  return (
    <span className="field-label">
      {children}
      {required ? (
        <span className="field-required" aria-hidden="true">
          {' '}
          *
        </span>
      ) : null}
    </span>
  )
}
