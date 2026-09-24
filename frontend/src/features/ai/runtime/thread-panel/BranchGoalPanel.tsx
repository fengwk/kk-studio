import { useState, type FormEvent } from 'react'
import { X, Target, CheckCircle2, AlertTriangle, Clock } from 'lucide-react'
import type { BranchGoalProgressResult } from '@/features/ai/runtime/goal-progress'
import { useI18n } from '@/shared/i18n'

export interface BranchGoalSetting {
  id: string
  text: string
}

export interface BranchGoalPanelProps {
  goal: BranchGoalSetting | null
  progress: BranchGoalProgressResult
  busy?: boolean
  readOnly?: boolean
  onSubmitGoal: (goalText: string) => Promise<void> | void
  onClearGoal: () => Promise<void> | void
  onClose: () => void
}

export function BranchGoalPanel({
  goal,
  progress,
  busy = false,
  readOnly = false,
  onSubmitGoal,
  onClearGoal,
  onClose,
}: BranchGoalPanelProps) {
  const { t } = useI18n()
  const [inputText, setInputText] = useState('')
  const [error, setError] = useState<string | null>(null)

  const charCount = Array.from(inputText).length
  const trimmed = inputText.trim()
  const trimmedCount = Array.from(trimmed).length

  function handleSet(e: FormEvent) {
    e.preventDefault()
    if (readOnly || busy) {
      return
    }
    if (trimmedCount === 0) {
      setError(t('ai.runtime.goal.errorEmpty'))
      return
    }
    if (charCount > 2000) {
      setError(t('ai.runtime.goal.errorTooLong'))
      return
    }
    setError(null)
    onSubmitGoal(trimmed)
  }

  function handleClear() {
    if (readOnly || busy || !goal) {
      return
    }
    setError(null)
    onClearGoal()
  }

  return (
    <section className="branch-goal-panel" aria-label={t('ai.runtime.goal.title')}>
      <header className="branch-goal-header">
        <div className="branch-goal-header-title">
          <Target className="branch-goal-icon" aria-hidden="true" />
          <h3>{t('ai.runtime.goal.title')}</h3>
        </div>
        <button
          type="button"
          className="branch-goal-close-btn"
          aria-label={t('shared.close')}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
      </header>

      <div className="branch-goal-body">
        {/* 当前目标展示 */}
        <div className="branch-goal-current-card">
          <div className="branch-goal-card-header">
            <span className="branch-goal-card-label">{t('ai.runtime.goal.currentGoal')}</span>
            {goal ? (
              <span className="branch-goal-id-badge" title={goal.id}>
                ID: {goal.id.slice(0, 8)}
              </span>
            ) : null}
          </div>
          {goal ? (
            <div className="branch-goal-text">{goal.text}</div>
          ) : (
            <div className="branch-goal-empty-text">{t('ai.runtime.goal.emptyGoal')}</div>
          )}
        </div>

        {/* Agent 汇报进展展示 */}
        {progress.active ? (
          <div className={`branch-goal-report-card ${progress.active.status}`}>
            <div className="branch-goal-report-header">
              <div className="branch-goal-report-status">
                {progress.active.status === 'complete' ? (
                  <CheckCircle2 className="branch-goal-report-icon success" aria-hidden="true" />
                ) : (
                  <AlertTriangle className="branch-goal-report-icon warning" aria-hidden="true" />
                )}
                <span className="branch-goal-report-badge">
                  {progress.active.status === 'complete'
                    ? t('ai.runtime.goal.statusCompleteReported')
                    : t('ai.runtime.goal.statusBlockedReported')}
                </span>
              </div>
              <span className="branch-goal-disclaimer">
                {t('ai.runtime.goal.agentReportDisclaimer')}
              </span>
            </div>
            <div className="branch-goal-report-reason">{progress.active.reason}</div>
            <div className="branch-goal-report-time">
              <Clock className="branch-goal-time-icon" aria-hidden="true" />
              <span>{progress.active.reportedAt}</span>
            </div>
          </div>
        ) : progress.stale ? (
          <div className="branch-goal-stale-card">
            <span className="branch-goal-stale-notice">
              {t('ai.runtime.goal.staleReportNotice')}
            </span>
          </div>
        ) : null}

        {/* 操作区 */}
        {!readOnly ? (
          <form className="branch-goal-form" onSubmit={handleSet}>
            <label htmlFor="goal-input" className="branch-goal-input-label">
              {goal ? t('ai.runtime.goal.updateGoalLabel') : t('ai.runtime.goal.setGoalLabel')}
            </label>
            <textarea
              id="goal-input"
              className="branch-goal-textarea"
              rows={3}
              placeholder={t('ai.runtime.goal.inputPlaceholder')}
              value={inputText}
              disabled={busy}
              onChange={(e) => {
                setInputText(e.target.value)
                setError(null)
              }}
            />
            <div className="branch-goal-form-meta">
              <span className={`branch-goal-counter ${charCount > 2000 ? 'overflow' : ''}`}>
                {charCount} / 2000
              </span>
              {error ? <span className="branch-goal-error-inline">{error}</span> : null}
            </div>

            <div className="branch-goal-actions">
              <button
                type="submit"
                className="btn-primary branch-goal-submit-btn"
                disabled={busy || !inputText.trim() || charCount > 2000}
              >
                {busy ? t('ai.runtime.goal.busy') : t('ai.runtime.goal.setGoalBtn')}
              </button>
              {goal ? (
                <button
                  type="button"
                  className="btn-danger branch-goal-clear-btn"
                  disabled={busy}
                  onClick={handleClear}
                >
                  {t('ai.runtime.goal.clearGoalBtn')}
                </button>
              ) : null}
            </div>
          </form>
        ) : (
          <div className="branch-goal-readonly-notice">
            {t('ai.runtime.goal.readOnlyNotice')}
          </div>
        )}
      </div>
    </section>
  )
}
