import { RUN_STEPS } from '@/features/canvas/data'
import type { AgentRunNode } from '@/features/canvas/types'

/** Pure presentation for an Agent Run card with step progress and controls. */
export function RunThreadMessage({
  run,
  onAction,
}: {
  run: AgentRunNode
  onAction: (action: 'pause' | 'resume' | 'retry') => void
}) {
  const running = run.status === 'running'
  const paused = run.status === 'paused'
  const control = running ? '暂停' : paused ? '继续' : '重试'
  const action = running ? 'pause' : paused ? 'resume' : 'retry'

  return (
    <div className="thread-message run">
      <strong>
        Agent Run ·
        {' '}
        {run.title}
      </strong>
      <div>
        {RUN_STEPS.map((step, stepIndex) => {
          const done = stepIndex < run.progress
          const current = running && stepIndex === run.progress
          return (
            <div key={step} className={`run-step ${done ? 'done' : ''} ${current ? 'current' : ''}`}>
              <b>{done ? '✓' : current ? '●' : '○'}</b>
              {step}
            </div>
          )
        })}
      </div>
      <small>
        {running ? '运行中' : paused ? '已暂停' : '已完成'}
        {' '}
        ·
        {' '}
        {run.progress}
        /
        {run.total}
      </small>
      <div className="run-controls">
        <button type="button" onClick={() => onAction(action)}>{control}</button>
      </div>
    </div>
  )
}
