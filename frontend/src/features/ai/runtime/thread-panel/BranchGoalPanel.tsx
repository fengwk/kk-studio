import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { AlertTriangle, CheckCircle2, Clock } from 'lucide-react'
import type { BranchGoalProgressResult } from '@/features/ai/runtime/goal-progress'
import { ThreadInteractionPanel } from '@/features/ai/runtime/thread-panel/ThreadInteractionPanel'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { useI18n } from '@/shared/i18n'
import '@/features/ai/runtime/thread-panel/BranchGoalPanel.css'

export interface BranchGoalSetting {
  id: string
  text: string
}

export interface BranchGoalPanelProps {
  goal: BranchGoalSetting | null
  progress: BranchGoalProgressResult
  busy?: boolean
  readOnly?: boolean
  /**
   * 受控的 Goal 编辑区文本。绑定 Thread 由 Thread 草稿记录提供，使 Stop 回执恢复的
   * 目标文本在面板关闭/重新打开或刷新后仍然存在；省略时面板自持文本。
   */
  draftText?: string
  onDraftTextChange?: (text: string) => void
  onSubmitGoal: (goalText: string) => Promise<void> | void
  onClearGoal: () => Promise<void> | void
  onClose: () => void
}

/**
 * thread 目标轻量面板；只读时仅展示事实，Agent 进展报告不代表系统验收。
 */
export function BranchGoalPanel({
  goal,
  progress,
  busy = false,
  readOnly = false,
  draftText,
  onDraftTextChange,
  onSubmitGoal,
  onClearGoal,
  onClose,
}: BranchGoalPanelProps) {
  const { t } = useI18n()
  const [internalText, setInternalText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const inputText = draftText ?? internalText
  const textareaId = useId()
  const panelRef = useRef<HTMLElement>(null)

  // 只读面板聚焦自身，使没有输入框时仍可通过 Esc 关闭。
  useEffect(() => {
    if (readOnly) {
      panelRef.current?.focus({ preventScroll: true })
    }
  }, [readOnly])

  function changeText(next: string) {
    if (draftText === undefined) {
      setInternalText(next)
    }
    onDraftTextChange?.(next)
  }

  const charCount = Array.from(inputText).length
  const trimmed = inputText.trim()
  const trimmedCount = Array.from(trimmed).length

  function handleSubmit() {
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
    void onSubmitGoal(trimmed)
  }

  function handleClear() {
    if (readOnly || busy || !goal) {
      return
    }
    setError(null)
    void onClearGoal()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    // 内层菜单/下拉或已消费的按键让路；输入法 composing 期间不触发关闭。
    if (event.defaultPrevented || event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key !== 'Escape') {
      return
    }
    // 仅消费本面板的关闭操作，不传播至父级。
    event.preventDefault()
    event.stopPropagation()
    onClose()
  }

  const showReport = progress.active != null
  const showStale = !showReport && progress.stale != null
  const canSubmit = !busy && trimmedCount > 0 && charCount <= 2000

  return (
    <ThreadInteractionPanel
      title={t('ai.runtime.goal.title')}
      bodyClassName="goal-panel-body"
      busy={busy}
      panelRef={readOnly ? panelRef : undefined}
      onClose={onClose}
      onKeyDown={handleKeyDown}
      footer={readOnly ? undefined : (
        <div className="goal-panel-actions">
          <Button type="button" onClick={handleSubmit} disabled={!canSubmit}>
            {busy ? t('ai.runtime.goal.busy') : t('ai.runtime.goal.setGoalBtn')}
          </Button>
          {goal ? (
            <Button type="button" variant="ghost" danger onClick={handleClear} disabled={busy}>
              {t('ai.runtime.goal.clearGoalBtn')}
            </Button>
          ) : null}
        </div>
      )}
    >
      <div className="goal-panel-current">
        <span className="goal-panel-section-label">{t('ai.runtime.goal.currentGoal')}</span>
        {goal ? (
          <div className="goal-panel-text">{goal.text}</div>
        ) : (
          <div className="goal-panel-empty">{t('ai.runtime.goal.emptyGoal')}</div>
        )}
      </div>

      {showReport ? (
        <div className="goal-panel-report-block">
          <span className="goal-panel-section-label">{t('ai.runtime.goal.agentReportTitle')}</span>
          <div className={`goal-panel-report ${progress.active!.status}`}>
            <div className="goal-panel-report-head">
              <span className={`goal-panel-report-title ${progress.active!.status}`}>
                {progress.active!.status === 'complete' ? (
                  <CheckCircle2 aria-hidden="true" />
                ) : (
                  <AlertTriangle aria-hidden="true" />
                )}
                {progress.active!.status === 'complete'
                  ? t('ai.runtime.goal.statusCompleteReported')
                  : t('ai.runtime.goal.statusBlockedReported')}
              </span>
              <span className="goal-panel-report-disclaimer">
                {t('ai.runtime.goal.agentReportDisclaimer')}
              </span>
            </div>
            <div className="goal-panel-report-reason">{progress.active!.reason}</div>
            <div className="goal-panel-report-time">
              <Clock aria-hidden="true" />
              <span>{progress.active!.reportedAt}</span>
            </div>
          </div>
        </div>
      ) : showStale ? (
        <div className="goal-panel-stale" role="note">
          {t('ai.runtime.goal.staleReportNotice')}
        </div>
      ) : null}

      {readOnly ? (
        <div className="goal-panel-readonly">{t('ai.runtime.goal.readOnlyNotice')}</div>
      ) : (
        <div className="goal-panel-form">
          <label htmlFor={textareaId}>
            <FieldLabel>{goal ? t('ai.runtime.goal.updateGoalLabel') : t('ai.runtime.goal.setGoalLabel')}</FieldLabel>
          </label>
          <TextArea
            id={textareaId}
            rows={3}
            autoFocus
            placeholder={t('ai.runtime.goal.inputPlaceholder')}
            value={inputText}
            disabled={busy}
            invalid={charCount > 2000}
            onChange={(event) => {
              changeText(event.target.value)
              setError(null)
            }}
          />
          <div className="goal-panel-meta">
            <span className={`goal-panel-counter${charCount > 2000 ? ' overflow' : ''}`}>
              {charCount} / 2000
            </span>
            {error ? <span className="goal-panel-error" role="alert">{error}</span> : null}
          </div>
        </div>
      )}
    </ThreadInteractionPanel>
  )
}
