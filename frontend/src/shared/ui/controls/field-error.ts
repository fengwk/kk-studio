import { useId } from 'react'

/**
 * 字段错误的可访问关联：错误文案与出错控件必须引用同一个 id，且文案要渲染在
 * 包裹控件的 `<label>` 之外——否则读屏会把错误文案算进控件的可访问名称。
 */

/** 合成 aria-describedby：忽略未提供的来源，保留调用方给出的顺序。 */
export function joinDescribedBy(...ids: Array<string | undefined>): string | undefined {
  const present = ids.filter((id): id is string => Boolean(id))
  return present.length > 0 ? present.join(' ') : undefined
}

/** 字段错误的稳定 id；未出错时返回 undefined，便于直接交给 aria-describedby。 */
export function useFieldErrorId(error: string | null | undefined): string | undefined {
  const generatedId = useId()
  return error ? `field-error-${generatedId.replace(/[^a-zA-Z0-9_-]/g, '')}` : undefined
}
