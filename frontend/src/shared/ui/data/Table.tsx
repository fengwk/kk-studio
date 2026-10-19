import type { TableHTMLAttributes } from 'react'
import './data.css'

/** 紧凑数据表：横向滚动容器内的原生 table，表头为小号弱化文字。 */
export function Table({ className, ...rest }: TableHTMLAttributes<HTMLTableElement>) {
  return (
    <div className="ui-table-scroll">
      <table className={['ui-table', className].filter(Boolean).join(' ')} {...rest} />
    </div>
  )
}
