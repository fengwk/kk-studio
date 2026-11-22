import { AlertCircle, Check, X } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Code } from '@/shared/ui/data/Mono'
import type { useI18n } from '@/shared/i18n'
import type { AskUserQuestionnaire } from './questionnaire-types'

type CompletedResult = { declined: boolean; answers?: string[][] }

type Translate = ReturnType<typeof useI18n>['t']

/** 已物化终态：拒绝与完成两种只读视图。 */
export function QuestionnaireCompletedView({
  completed,
  questionnaire,
  t,
}: {
  completed: CompletedResult
  questionnaire: AskUserQuestionnaire | null
  t: Translate
}) {
  if (completed.declined) {
    return (
      <div className="interaction-questionnaire-card is-completed is-declined">
        <div className="interaction-questionnaire-badge declined">
          <X aria-hidden="true" size={14} />
          <span>{t('ai.interaction.declined')}</span>
        </div>
      </div>
    )
  }

  return (
    <div className="interaction-questionnaire-card is-completed">
      <span className="interaction-questionnaire-badge completed">
        <Check aria-hidden="true" size={14} />
        <span>{t('shared.completed')}</span>
      </span>
      <div className="interaction-questions-list">
        {questionnaire?.questions.map((q, idx) => {
          const ans = completed.answers?.[idx] || []
          return (
            <div key={idx} className="interaction-question-item">
              <div className="interaction-question-title">
                <span className="interaction-question-idx">{idx + 1}.</span>
                <span className="interaction-question-text">{q.question}</span>
              </div>
              <div className="interaction-completed-answers">
                {ans.map((a, aIdx) => (
                  <span key={aIdx} className="interaction-answer-tag">
                    {a}
                  </span>
                ))}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

/**
 * 问卷参数无法解析：先说明当前阻塞与可继续的动作，原始参数只作按需诊断。
 *
 * 拒答是现有的退出路径，失败仍由同一草稿 store 公布错误。
 */
export function QuestionnaireInvalidView({
  argumentsJson,
  onDecline,
  disabled,
  error,
  nonRecoverable,
  t,
}: {
  argumentsJson: string
  onDecline: () => void
  disabled: boolean
  error?: string | null
  nonRecoverable: boolean
  t: Translate
}) {
  return (
    <div className="interaction-questionnaire-card is-invalid">
      <div className="interaction-error-banner" role="alert">
        <AlertCircle size={16} aria-hidden="true" />
        <span>{t('ai.interaction.questionnaireInvalid')}</span>
      </div>
      {error ? (
        <QuestionnaireErrorBanner error={error} nonRecoverable={nonRecoverable} />
      ) : null}
      <details className="interaction-diagnostics">
        <summary>{t('ai.interaction.rawArguments')}</summary>
        <Code className="interaction-raw-pre">{argumentsJson}</Code>
      </details>
      <div className="interaction-actions-bar">
        <Button
          variant="ghost"
          danger
          className="interaction-decline-btn"
          disabled={disabled}
          onClick={onDecline}
        >
          {t('ai.interaction.decline')}
        </Button>
      </div>
    </div>
  )
}

/** 提交失败横幅：非可恢复错误额外标记样式。 */
export function QuestionnaireErrorBanner({
  error,
  nonRecoverable,
}: {
  error: string
  nonRecoverable: boolean
}) {
  return (
    <div
      className={`interaction-error-banner ${nonRecoverable ? 'is-non-recoverable' : ''}`}
      role="alert"
    >
      <AlertCircle size={16} aria-hidden="true" />
      <span>{error}</span>
    </div>
  )
}
