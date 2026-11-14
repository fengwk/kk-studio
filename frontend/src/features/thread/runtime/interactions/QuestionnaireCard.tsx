import { useQueryClient } from '@tanstack/react-query'
import { Check, RotateCw } from 'lucide-react'
import { useMemo } from 'react'
import { useI18n } from '@/shared/i18n'
import { createUuid } from '@/shared/lib/uuid'
import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import {
  interactionDraftStore,
  useInteractionDraft,
} from './interaction-draft-store'
import {
  normalizeAnswers,
  parseCompletedResult,
  parseQuestionnaire,
} from './questionnaire-parser'
import {
  applyCustomText,
  submitQuestionnaireAnswer,
  toggleSingleOrMultiOption,
} from './questionnaire-submission'
import {
  QuestionnaireCompletedView,
  QuestionnaireErrorBanner,
  QuestionnaireInvalidView,
} from './questionnaire-views'
import './interaction-cards.css'
import type { HarnessToolInputResultDTO } from '@/shared/api/generated/contracts'

interface QuestionnaireCardProps {
  interactionId: string
  threadId: string
  argumentsJson: string
  result?: unknown
  onSuccess?: (result: HarnessToolInputResultDTO) => void
}

export function QuestionnaireCard({
  interactionId,
  threadId,
  argumentsJson,
  result,
  onSuccess,
}: QuestionnaireCardProps) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const draft = useInteractionDraft(interactionId)

  const questionnaire = useMemo(
    () => parseQuestionnaire(argumentsJson),
    [argumentsJson],
  )
  const completed = useMemo(
    () => parseCompletedResult(result),
    [result],
  )

  if (completed) {
    return <QuestionnaireCompletedView completed={completed} questionnaire={questionnaire} t={t} />
  }
  if (!questionnaire) {
    return <QuestionnaireInvalidView argumentsJson={argumentsJson} t={t} />
  }

  const validAnswers = normalizeAnswers(questionnaire, draft.questionDrafts)
  const canSubmit = validAnswers != null && !draft.isSubmitting && !draft.isNonRecoverable
  const locked = draft.isSubmitting || draft.isNonRecoverable

  const submit = (
    submissionId: string,
    answers?: string[][],
    declined?: boolean,
  ) => {
    void submitQuestionnaireAnswer({
      interactionId,
      threadId,
      submissionId,
      answers,
      declined,
      queryClient,
      conflictErrorText: t('ai.interaction.nonRecoverable'),
      fallbackErrorText: t('ai.common.operationFailed'),
      onSuccess,
    })
  }

  const handleSelectOption = (qIdx: number, label: string, multiple: boolean) => {
    if (locked) {
      return
    }
    interactionDraftStore.updateQuestionDraft(interactionId, qIdx, (prev) =>
      toggleSingleOrMultiOption(prev, label, multiple),
    )
  }

  const handleCustomTextChange = (qIdx: number, text: string, multiple: boolean) => {
    if (locked) {
      return
    }
    interactionDraftStore.updateQuestionDraft(interactionId, qIdx, (prev) =>
      applyCustomText(prev, text, multiple),
    )
  }

  const handleSubmit = () => {
    if (!validAnswers) {
      return
    }
    const submissionId = createUuid()
    interactionDraftStore.freezeSubmission(interactionId, submissionId, validAnswers, false)
    submit(submissionId, validAnswers, false)
  }

  const handleRetry = () => {
    if (draft.isNonRecoverable || !draft.frozenSubmissionId) {
      return
    }
    submit(draft.frozenSubmissionId, draft.frozenAnswers, draft.frozenDeclined)
  }

  const handleDecline = () => {
    if (locked) {
      return
    }
    const submissionId = createUuid()
    interactionDraftStore.freezeSubmission(interactionId, submissionId, undefined, true)
    submit(submissionId, undefined, true)
  }

  return (
    <div className="interaction-questionnaire-card">
      <div className="interaction-questions-list">
        {questionnaire.questions.map((q, qIdx) => {
          const qDraft = draft.questionDrafts[qIdx] || {
            selectedLabels: [],
            customText: '',
          }
          const isMultiple = Boolean(q.multiple)

          return (
            <div key={qIdx} className="interaction-question-item">
              <div className="interaction-question-title">
                <span className="interaction-question-idx">{qIdx + 1}.</span>
                <span className="interaction-question-text">{q.question}</span>
                <span className="interaction-question-type-tag">
                  {isMultiple
                    ? t('ai.interaction.multipleChoice')
                    : t('ai.interaction.singleChoice')}
                </span>
              </div>

              {q.options && q.options.length > 0 ? (
                <div className="interaction-options-grid">
                  {q.options.map((opt, optIdx) => {
                    const isSelected = qDraft.selectedLabels.includes(opt.label)
                    return (
                      <button
                        key={optIdx}
                        type="button"
                        className={`interaction-option-btn ${isSelected ? 'is-selected' : ''}`}
                        disabled={locked}
                        onClick={() => handleSelectOption(qIdx, opt.label, isMultiple)}
                      >
                        <span className="interaction-option-label-row">
                          <span className="interaction-option-indicator">
                            {isSelected ? <Check size={12} /> : null}
                          </span>
                          <span className="interaction-option-label">{opt.label}</span>
                          {opt.recommended ? (
                            <span className="interaction-recommended-tag">
                              {t('ai.interaction.recommended')}
                            </span>
                          ) : null}
                        </span>
                        {opt.description ? (
                          <span className="interaction-option-desc">
                            {opt.description}
                          </span>
                        ) : null}
                      </button>
                    )
                  })}
                </div>
              ) : null}

              {/* 固定提供的自定义输入框 */}
              <div className="interaction-custom-input-wrap">
                <TextInput
                  type="text"
                  className="interaction-custom-input"
                  placeholder={t('ai.interaction.customInputPlaceholder')}
                  value={qDraft.customText}
                  disabled={locked}
                  onChange={(e) =>
                    handleCustomTextChange(qIdx, e.target.value, isMultiple)
                  }
                />
              </div>
            </div>
          )
        })}
      </div>

      {draft.error ? (
        <QuestionnaireErrorBanner error={draft.error} nonRecoverable={Boolean(draft.isNonRecoverable)} />
      ) : null}

      <div className="interaction-actions-bar">
        <Button
          variant="ghost"
          className="interaction-decline-btn"
          disabled={locked}
          onClick={handleDecline}
        >
          {t('ai.interaction.decline')}
        </Button>

        <div className="interaction-actions-right">
          {!draft.isNonRecoverable && draft.error && draft.frozenSubmissionId ? (
            <Button
              className="interaction-retry-btn"
              disabled={draft.isSubmitting}
              onClick={handleRetry}
            >
              <RotateCw size={14} className={draft.isSubmitting ? 'animate-spin' : ''} />
              <span>{t('ai.interaction.retry')}</span>
            </Button>
          ) : (
            <Button
              className="interaction-submit-btn"
              disabled={!canSubmit}
              onClick={handleSubmit}
            >
              {draft.isSubmitting ? t('ai.interaction.submitting') : t('ai.interaction.submit')}
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}
