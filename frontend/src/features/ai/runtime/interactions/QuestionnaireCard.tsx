import { useQueryClient } from '@tanstack/react-query'
import { AlertCircle, Check, RotateCw, X } from 'lucide-react'
import { useMemo } from 'react'
import { isConflictError } from '@/shared/api/client'
import type { HarnessToolInputResultDTO } from '@/shared/api/contracts/ai-interaction'
import { interactionService } from '@/shared/api/interaction-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid } from '@/shared/lib/uuid'
import {
  interactionDraftStore,
  useInteractionDraft,
} from './interaction-draft-store'
import {
  normalizeAnswers,
  parseCompletedResult,
  parseQuestionnaire,
} from './questionnaire-parser'
import './interaction-cards.css'

export interface QuestionnaireCardProps {
  interactionId: string
  threadId: string
  argumentsJson: string
  resultJson?: string | null
  onSuccess?: (result: HarnessToolInputResultDTO) => void
}

export function QuestionnaireCard({
  interactionId,
  threadId,
  argumentsJson,
  resultJson,
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
    () => parseCompletedResult(resultJson),
    [resultJson],
  )

  // 1. 如果已物化终态结果，展示完成状态视图
  if (completed) {
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

  // 2. 问卷无法解析时的回退展示
  if (!questionnaire) {
    return (
      <div className="interaction-questionnaire-card is-invalid">
        <span className="interaction-error-text">
          {t('ai.common.operationFailed')}
        </span>
        <pre className="interaction-raw-pre">{argumentsJson}</pre>
      </div>
    )
  }

  // 3. 活动交互态（正在等待输入）
  const validAnswers = normalizeAnswers(questionnaire, draft.questionDrafts)
  const canSubmit = validAnswers != null && !draft.isSubmitting && !draft.isNonRecoverable

  const handleSelectOption = (qIdx: number, label: string, multiple: boolean) => {
    if (draft.isSubmitting || draft.isNonRecoverable) return
    interactionDraftStore.updateQuestionDraft(interactionId, qIdx, (prev) => {
      if (!multiple) {
        // 单选：恰好选择一个选项，清空自定义输入
        return {
          selectedLabels: prev.selectedLabels[0] === label ? [] : [label],
          customText: '',
        }
      }
      // 多选：切换选项选中态
      const exists = prev.selectedLabels.includes(label)
      const nextLabels = exists
        ? prev.selectedLabels.filter((item) => item !== label)
        : [...prev.selectedLabels, label]
      return {
        ...prev,
        selectedLabels: nextLabels,
      }
    })
  }

  const handleCustomTextChange = (qIdx: number, text: string, multiple: boolean) => {
    if (draft.isSubmitting || draft.isNonRecoverable) return
    interactionDraftStore.updateQuestionDraft(interactionId, qIdx, (prev) => {
      if (!multiple) {
        // 单选：输入自定义文本时，清空预置选项选择
        return {
          selectedLabels: [],
          customText: text,
        }
      }
      return {
        ...prev,
        customText: text,
      }
    })
  }

  const doSubmit = async (
    submissionId: string,
    answers?: string[][],
    declined?: boolean,
  ) => {
    interactionDraftStore.setSubmitting(interactionId, true)
    try {
      const res = await interactionService.submitToolInput(interactionId, {
        threadId,
        submissionId,
        answers,
        declined,
      })
      // 提交成功，清理草稿并刷新 queries
      interactionDraftStore.clearDraft(interactionId)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all }),
        queryClient.invalidateQueries({
          queryKey: queryKeys.threads.snapshot(threadId),
        }),
      ])
      onSuccess?.(res)
    } catch (err: unknown) {
      if (isConflictError(err) || (err instanceof Error && err.message.includes('409'))) {
        // 409 或不可恢复冲突：保留草稿作查看，禁止过期提交
        interactionDraftStore.setSubmissionError(
          interactionId,
          t('ai.interaction.nonRecoverable'),
          true,
        )
      } else {
        // 普通网络/服务错误：允许使用同一 submissionId 精确重试
        const msg = err instanceof Error ? err.message : t('ai.common.operationFailed')
        interactionDraftStore.setSubmissionError(interactionId, msg, false)
      }
    }
  }

  const handleSubmit = () => {
    if (!validAnswers) return
    const submissionId = createUuid()
    interactionDraftStore.freezeSubmission(
      interactionId,
      submissionId,
      validAnswers,
      false,
    )
    void doSubmit(submissionId, validAnswers, false)
  }

  const handleRetry = () => {
    if (draft.isNonRecoverable || !draft.frozenSubmissionId) return
    void doSubmit(
      draft.frozenSubmissionId,
      draft.frozenAnswers,
      draft.frozenDeclined,
    )
  }

  const handleDecline = () => {
    if (draft.isSubmitting || draft.isNonRecoverable) return
    const submissionId = createUuid()
    interactionDraftStore.freezeSubmission(
      interactionId,
      submissionId,
      undefined,
      true,
    )
    void doSubmit(submissionId, undefined, true)
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
                        disabled={draft.isSubmitting || draft.isNonRecoverable}
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
                <input
                  type="text"
                  className="interaction-custom-input"
                  placeholder={t('ai.interaction.customInputPlaceholder')}
                  value={qDraft.customText}
                  disabled={draft.isSubmitting || draft.isNonRecoverable}
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
        <div
          className={`interaction-error-banner ${draft.isNonRecoverable ? 'is-non-recoverable' : ''}`}
        >
          <AlertCircle size={16} aria-hidden="true" />
          <span>{draft.error}</span>
        </div>
      ) : null}

      <div className="interaction-actions-bar">
        <button
          type="button"
          className="ghost-btn interaction-decline-btn"
          disabled={draft.isSubmitting || draft.isNonRecoverable}
          onClick={handleDecline}
        >
          {t('ai.interaction.decline')}
        </button>

        <div className="interaction-actions-right">
          {!draft.isNonRecoverable && draft.error && draft.frozenSubmissionId ? (
            <button
              type="button"
              className="btn-primary interaction-retry-btn"
              disabled={draft.isSubmitting}
              onClick={handleRetry}
            >
              <RotateCw size={14} className={draft.isSubmitting ? 'animate-spin' : ''} />
              <span>{t('ai.interaction.retry')}</span>
            </button>
          ) : (
            <button
              type="button"
              className="btn-primary interaction-submit-btn"
              disabled={!canSubmit}
              onClick={handleSubmit}
            >
              {draft.isSubmitting ? t('ai.interaction.submitting') : t('ai.interaction.submit')}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
