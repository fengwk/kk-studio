import { AlertCircle, Check, X } from 'lucide-react'
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
      <div className="interaction-questionnaire-header">
        <span className="interaction-questionnaire-badge completed">
          <Check aria-hidden="true" size={14} />
          <span>{t('shared.completed')}</span>
        </span>
      </div>
      <div className="interaction-questions-list">
        {questionnaire?.questions.map((q, idx) => {
          const ans = completed.answers?.[idx] || []
          return (
            <div key={idx} className="interaction-question-item">
              <div className="interaction-question-title">
                <span className="interaction-question-idx">{idx + 1}.</span>
                <span>{q.question}</span>
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

/** 问卷参数无法解析时的回退：保留原始 JSON 供排查。 */
export function QuestionnaireInvalidView({
  argumentsJson,
  t,
}: {
  argumentsJson: string
  t: Translate
}) {
  return (
    <div className="interaction-questionnaire-card is-invalid">
      <span className="interaction-error-text">
        {t('ai.common.operationFailed')}
      </span>
      <Code className="interaction-raw-pre">{argumentsJson}</Code>
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
    >
      <AlertCircle size={16} aria-hidden="true" />
      <span>{error}</span>
    </div>
  )
}
