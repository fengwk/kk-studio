import type { HTMLAttributes } from 'react'
import './layout.css'

/** 工具条：一行可换行的搜索、筛选与动作。 */
export function Toolbar({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={['ui-toolbar', className].filter(Boolean).join(' ')} {...rest} />
}

/** 工具条内靠右的动作组。 */
export function ToolbarActions({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={['ui-toolbar-actions', className].filter(Boolean).join(' ')} {...rest} />
}
