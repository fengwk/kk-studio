import type { ReactNode, Ref } from 'react'
import './ResourceGrid.css'

export interface ResourceGridProps {
  children: ReactNode
  /**
   * 栅格元素自身的修饰类名。调用方在 `.resource-grid` 之外改写列模板时（如画布库的 280px 列），
   * 必须把同一个类名交给骨架屏，骨架才会按同一列模板绘制首行。
   */
  className?: string
  ref?: Ref<HTMLDivElement>
}

/**
 * 资源栅格：自适应列数、16px 间距；新建卡与资源卡共用同一栅格语言。
 * 窄窗口先减少列数，再让卡内内容自然换行，不缩小文字或挤掉操作。
 */
export function ResourceGrid({ children, className, ref }: ResourceGridProps) {
  return (
    <div ref={ref} className={['resource-grid', className].filter(Boolean).join(' ')}>
      {children}
    </div>
  )
}
