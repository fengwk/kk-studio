import { RUN_STEPS } from '@/features/canvas/data'
import type { AgentRunNode } from '@/features/canvas/types'
import { useI18n } from '@/shared/i18n'

/** Agent Run 卡片（含步骤进度与控件）的纯展示组件。 */
export function RunThreadMessage({
  run,
  onAction,
}: {
  run: AgentRunNode
  onAction: (action: 'pause' | 'resume' | 'retry') => void
}) {
  const { t } = useI18n()
  const running = run.status === 'running'
  const paused = run.status === 'paused'
  const control = t(
    running
      ? 'canvas.agent.run.control.pause'
      : paused
        ? 'canvas.agent.run.control.resume'
        : 'canvas.agent.run.control.retry',
  )
  const status = t(
    running
      ? 'canvas.agent.run.status.running'
      : paused
        ? 'canvas.agent.run.status.paused'
        : 'canvas.agent.run.status.completed',
  )
  const action = running ? 'pause' : paused ? 'resume' : 'retry'

  return (
    <div className="thread-message run">
      <strong>
        {t('canvas.agent.run.label')} ·
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
              {t(step)}
            </div>
          )
        })}
      </div>
      <small>
        {t('canvas.agent.run.status.withProgress', {
          status,
          progress: run.progress,
          total: run.total,
        })}
      </small>
      <div className="run-controls">
        <button type="button" onClick={() => onAction(action)}>{control}</button>
      </div>
    </div>
  )
}
