import type { ReactNode } from 'react'

/**
 * 字段错误文案：`id` 供出错控件的 aria-describedby 引用，必须渲染在包裹控件与其标签的
 * 容器内、但不在包裹控件的 `<label>` 里，否则文案会并入控件的可访问名称。
 * 需要立即播报时由调用方传入 `role="alert"`；编辑器这类会把焦点移到第一处错误的场景
 * 保持无 role，避免与焦点播报重复。
 */
export function FieldError({
  id,
  role,
  children,
}: {
  id?: string
  role?: 'alert' | 'status'
  children: ReactNode
}) {
  return (
    <span id={id} className="field-error" role={role}>
      {children}
    </span>
  )
}
