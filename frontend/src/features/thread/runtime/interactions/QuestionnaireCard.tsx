import { useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import { Check, RotateCw } from 'lucide-react'
import { useId, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
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
import './interaction-card-content.css'
import type { HarnessToolInputResultDTO } from '@/shared/api/generated/contracts'

interface QuestionnaireCardProps {
  interactionId: string
  threadId: string
  argumentsJson: string
  result?: unknown
  onSuccess?: (result?: HarnessToolInputResultDTO) => void
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
  const applicationEvents = useApplicationEvents()
  const draft = useInteractionDraft(interactionId)
  const questionLabelIdBase = useId()
  /** 单选组只保留一个 Tab 停靠点：记录每题最近聚焦的选项。 */
  const [focusedOption, setFocusedOption] = useState<Record<number, number>>({})
  const optionRefs = useRef<Record<number, Array<HTMLButtonElement | null>>>({})

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

  const locked = Boolean(draft.isSubmitting || draft.isNonRecoverable)

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
      applicationEvents,
      conflictErrorText: t('ai.interaction.nonRecoverable'),
      conflictStillPendingText: t('ai.interaction.conflictStillPending'),
      fallbackErrorText: t('ai.common.operationFailed'),
      onSuccess,
    })
  }

  const handleDecline = () => {
    if (locked) {
      return
    }
    // 失败重试沿用已冻结的拒答身份，避免重复决定。
    if (draft.frozenSubmissionId && draft.frozenDeclined) {
      submit(draft.frozenSubmissionId, undefined, true)
      return
    }
    const submissionId = createUuid()
    interactionDraftStore.freezeSubmission(interactionId, submissionId, undefined, true)
    submit(submissionId, undefined, true)
  }

  if (!questionnaire) {
    return (
      <QuestionnaireInvalidView
        argumentsJson={argumentsJson}
        onDecline={handleDecline}
        disabled={locked}
        error={draft.error}
        nonRecoverable={Boolean(draft.isNonRecoverable)}
        t={t}
      />
    )
  }

  const validAnswers = normalizeAnswers(questionnaire, draft.questionDrafts)
  const canSubmit = validAnswers != null && !locked

  const handleSelectOption = (qIdx: number, label: string, multiple: boolean) => {
    if (locked) {
      return
    }
    interactionDraftStore.updateQuestionDraft(interactionId, qIdx, (prev) =>
      !multiple && prev.selectedLabels.includes(label)
        ? prev
        : toggleSingleOrMultiOption(prev, label, multiple),
    )
  }

  /** 方向键在单选组内移动焦点；选择仍由空格/回车（原生按钮激活）完成。 */
  const handleOptionsKeyDown = (
    event: KeyboardEvent<HTMLDivElement>,
    qIdx: number,
    optionCount: number,
  ) => {
    if (locked || event.defaultPrevented || event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    const { key } = event
    const isPrevious = key === 'ArrowLeft' || key === 'ArrowUp'
    const isNext = key === 'ArrowRight' || key === 'ArrowDown'
    if (!isPrevious && !isNext && key !== 'Home' && key !== 'End') {
      return
    }
    const buttons = optionRefs.current[qIdx] ?? []
    const focusedIndex = buttons.findIndex((button) => button === document.activeElement)
    const position = focusedIndex >= 0 ? focusedIndex : (focusedOption[qIdx] ?? 0)
    const nextIndex = key === 'Home'
      ? 0
      : key === 'End'
        ? optionCount - 1
        : (position + (isNext ? 1 : -1) + optionCount) % optionCount
    event.preventDefault()
    buttons[nextIndex]?.focus()
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

  return (
    <div className="interaction-questionnaire-card">
      <div className="interaction-questions-list">
        {questionnaire.questions.map((q, qIdx) => {
          const qDraft = draft.questionDrafts[qIdx] || {
            selectedLabels: [],
            customText: '',
          }
          const isMultiple = Boolean(q.multiple)
          const optionCount = q.options?.length ?? 0
          const selectedIndex = isMultiple
            ? -1
            : (q.options ?? []).findIndex((opt) => qDraft.selectedLabels.includes(opt.label))
          // 单选组只有一个 Tab 停靠点：已选项优先，否则停在最近聚焦的选项。
          const singleTabStop = selectedIndex >= 0
            ? selectedIndex
            : Math.max(0, Math.min(focusedOption[qIdx] ?? 0, optionCount - 1))
          const questionLabelId = `${questionLabelIdBase}-q${qIdx}`

          return (
            <div key={qIdx} className="interaction-question-item">
              <div className="interaction-question-title">
                <span className="interaction-question-idx">{qIdx + 1}.</span>
                <span className="interaction-question-text" id={questionLabelId}>
                  {q.question}
                </span>
                <span className="interaction-question-type-tag">
                  {isMultiple
                    ? t('ai.interaction.multipleChoice')
                    : t('ai.interaction.singleChoice')}
                </span>
              </div>

              {optionCount > 0 ? (
                <div
                  className="interaction-options-grid"
                  role={isMultiple ? 'group' : 'radiogroup'}
                  aria-labelledby={questionLabelId}
                  onKeyDown={(event) => handleOptionsKeyDown(event, qIdx, optionCount)}
                >
                  {(q.options ?? []).map((opt, optIdx) => {
                    const isSelected = qDraft.selectedLabels.includes(opt.label)
                    return (
                      <button
                        key={optIdx}
                        ref={(element) => {
                          const refs = optionRefs.current[qIdx] ?? []
                          refs[optIdx] = element
                          optionRefs.current[qIdx] = refs
                        }}
                        type="button"
                        // 单选呈现为单选组，多选呈现为复选框；选中态经 aria-checked 暴露给读屏。
                        role={isMultiple ? 'checkbox' : 'radio'}
                        aria-checked={isSelected}
                        tabIndex={
                          isMultiple ? undefined : (optIdx === singleTabStop ? 0 : -1)
                        }
                        className={`interaction-option-btn ${isSelected ? 'is-selected' : ''}`}
                        disabled={locked}
                        onFocus={
                          isMultiple
                            ? undefined
                            : () =>
                                setFocusedOption((prev) =>
                                  prev[qIdx] === optIdx ? prev : { ...prev, [qIdx]: optIdx },
                                )
                        }
                        onClick={() => handleSelectOption(qIdx, opt.label, isMultiple)}
                      >
                        <span className="interaction-option-label-row">
                          <span
                            className={`interaction-option-indicator ${isMultiple ? 'is-checkbox' : 'is-radio'}`}
                            aria-hidden="true"
                          >
                            {isSelected ? (
                              isMultiple ? (
                                <Check size={12} />
                              ) : (
                                <span className="interaction-option-dot" />
                              )
                            ) : null}
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
                  // 复用题干 id：读屏在输入第几题时能听到对应的问题。
                  aria-labelledby={questionLabelId}
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
