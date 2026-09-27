import { HelpCircle, X } from 'lucide-react'
import { useMemo } from 'react'
import type { ToolRendererProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'
import {
  parseCompletedResult,
  parseQuestionnaire,
} from '@/features/ai/runtime/interactions/questionnaire-parser'

export function AskUserToolRenderer({ message, expanded }: ToolRendererProps) {
  const { t } = useI18n()

  const questionnaire = useMemo(
    () => parseQuestionnaire(message.arguments),
    [message.arguments],
  )

  const completed = useMemo(
    () => (message.phase === 'result' ? parseCompletedResult(message.text) : null),
    [message.phase, message.text],
  )

  // 1. Result 阶段：展示已完成的回答或拒答
  if (message.phase === 'result') {
    if (completed?.declined) {
      return (
        <div className="ask-user-renderer-result is-declined">
          <span className="interaction-questionnaire-badge declined">
            <X size={12} />
            <span>{t('ai.interaction.declined')}</span>
          </span>
        </div>
      )
    }

    if (completed?.answers) {
      return (
        <div className="ask-user-renderer-result">
          <div className="interaction-questions-list">
            {completed.answers.map((row, idx) => (
              <div key={idx} className="interaction-completed-row">
                <span className="interaction-question-idx">{idx + 1}.</span>
                <div className="interaction-completed-answers">
                  {row.map((item, itemIdx) => (
                    <span key={itemIdx} className="interaction-answer-tag">
                      {item}
                    </span>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
      )
    }

    return (
      <div className="ask-user-renderer-result">
        <pre className="thread-tool-pre">{message.text}</pre>
      </div>
    )
  }

  // 2. Call 阶段（问卷本身）
  if (!questionnaire) {
    return (
      <div className="ask-user-renderer-fallback">
        <pre className="thread-tool-pre">{message.arguments}</pre>
      </div>
    )
  }

  return (
    <div className="ask-user-renderer-call">
      <div className="interaction-questions-list">
        {questionnaire.questions.map((q, idx) => (
          <div key={idx} className="ask-user-question-preview">
            <div className="interaction-question-title">
              <HelpCircle size={14} className="ask-user-q-icon" />
              <span className="interaction-question-idx">{idx + 1}.</span>
              <span className="interaction-question-text">{q.question}</span>
              <span className="interaction-question-type-tag">
                {q.multiple ? t('ai.interaction.multipleChoice') : t('ai.interaction.singleChoice')}
              </span>
            </div>
            {expanded && q.options && q.options.length > 0 ? (
              <div className="ask-user-options-preview">
                {q.options.map((opt, optIdx) => (
                  <div key={optIdx} className="ask-user-option-pill">
                    <span>{opt.label}</span>
                    {opt.recommended ? (
                      <span className="interaction-recommended-tag">
                        {t('ai.interaction.recommended')}
                      </span>
                    ) : null}
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        ))}
      </div>
    </div>
  )
}
