import type { ReactNode } from 'react'
import './cards.css'

/**
 * 资源栅格：自适应列数、16px 间距；新建卡与资源卡共用同一栅格语言。
 * 窄窗口先减少列数，再让卡内内容自然换行，不缩小文字或挤掉操作。
 */
export function ResourceGrid({
  children,
  className,
}: {
  children: ReactNode
  className?: string
}) {
  return <div className={['resource-grid', className].filter(Boolean).join(' ')}>{children}</div>
}
