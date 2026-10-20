import type { ReactNode } from 'react'
import './feedback.css'

/** 共享空态：说明没有内容的真实情况，并可附一个下一步动作。 */
export function EmptyState({ title, action }: { title: ReactNode; action?: ReactNode }) {
  return (
    <div className="ui-empty-state">
      <span>{title}</span>
      {action}
    </div>
  )
}
