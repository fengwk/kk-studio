import type { HTMLAttributes, ReactNode } from 'react'
import './layout.css'

export interface PanelProps extends Omit<HTMLAttributes<HTMLElement>, 'title'> {
  /** 面板标题；省略时不渲染头部。 */
  title?: ReactNode
  /** 头部右侧的动作区。 */
  actions?: ReactNode
}

/** 共享面板：普通表面 + 细边框 + 可选标题行，是设置、详情与诊断区块的共同容器。 */
export function Panel({ title, actions, className, children, ...rest }: PanelProps) {
  return (
    <section className={['ui-panel', className].filter(Boolean).join(' ')} {...rest}>
      {title || actions ? (
        <header className="ui-panel-header">
          <h3 className="ui-panel-title">{title}</h3>
          {actions ? <div className="ui-panel-actions">{actions}</div> : null}
        </header>
      ) : null}
      {children}
    </section>
  )
}
