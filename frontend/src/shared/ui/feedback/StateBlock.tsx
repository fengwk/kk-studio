import type { ReactNode } from 'react'
import './feedback.css'

/**
 * 共享状态块：加载中 / 空态 / 错误态的统一样式，可携带一个恢复动作。
 * `tone='danger'` 是失败：用 `role="alert"` 主动播报，其余语气保持静态文本。
 */
export function StateBlock({
  title,
  tone,
  action,
}: {
  title: string
  tone?: 'danger'
  action?: ReactNode
}) {
  return (
    <div
      className={`state-block ${tone === 'danger' ? 'danger' : ''}`}
      role={tone === 'danger' ? 'alert' : undefined}
    >
      {title}
      {action ? <div className="state-block-action">{action}</div> : null}
    </div>
  )
}
