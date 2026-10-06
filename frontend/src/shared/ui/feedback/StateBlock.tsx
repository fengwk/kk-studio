/**
 * 共享状态块：加载中 / 空态 / 错误态的统一样式。
 * `tone='danger'` 用于失败与冲突提示。
 */
export function StateBlock({ title, tone }: { title: string; tone?: 'danger' }) {
  return <div className={`state-block ${tone === 'danger' ? 'danger' : ''}`}>{title}</div>
}
