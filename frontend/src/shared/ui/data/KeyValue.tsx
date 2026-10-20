import type { HTMLAttributes, ReactNode } from 'react'
import './data.css'

/** 键值列表：标签在左、值在右，值可换行；用于详情与诊断面板。 */
export function KeyValue({ className, ...rest }: HTMLAttributes<HTMLDListElement>) {
  return <dl className={['ui-kv', className].filter(Boolean).join(' ')} {...rest} />
}

export interface KeyValueRowProps extends Omit<HTMLAttributes<HTMLDivElement>, 'children'> {
  label: ReactNode
  children: ReactNode
}

export function KeyValueRow({ label, children, className, ...rest }: KeyValueRowProps) {
  return (
    <div className={['ui-kv-row', className].filter(Boolean).join(' ')} {...rest}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}
