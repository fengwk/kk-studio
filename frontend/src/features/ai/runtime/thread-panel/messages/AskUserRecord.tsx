import { HelpCircle, X } from 'lucide-react'
import { useMemo } from 'react'
import {
  parseCompletedResult,
  parseQuestionnaire,
} from '@/features/ai/runtime/interactions/questionnaire-parser'
import { jsonContentText } from '@/features/ai/runtime/thread-timeline/content-utils'
import type { ToolContent } from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/**
 * ask_user 的只读问题/回答记录：展示冻结问卷与已物化的答案。
 *
 * 时间线不提供提交路径——人工回答只在根交互区完成；这里既不渲染输入控件，
 * 也不伪造未提交的选择。回答来自结果内容里的规范 JSON（answers/declined）。
 */
export function AskUserRecord({
  arguments: argumentsJson,
  contents,
}: {
  arguments: string
  contents: ToolContent[]
}) {
  const { t } = useI18n()
  const questionnaire = useMemo(
    () => parseQuestionnaire(argumentsJson),
    [argumentsJson],
  )
  const completed = useMemo(
    () => parseCompletedResult(resultJson(contents)),
    [contents],
  )
  if (questionnaire == null) {
    return null
  }
  return (
    <div className="ask-user-record">
      {completed?.declined ? (
        <span className="interaction-questionnaire-badge declined">
          <X size={12} aria-hidden="true" />
          <span>{t('ai.interaction.declined')}</span>
        </span>
      ) : null}
      <div className="interaction-questions-list">
        {questionnaire.questions.map((question, index) => (
          <div key={index} className="ask-user-question-record">
            <div className="interaction-question-title">
              <HelpCircle size={14} className="ask-user-q-icon" aria-hidden="true" />
              <span className="interaction-question-idx">{index + 1}.</span>
              <span className="interaction-question-text">{question.question}</span>
              <span className="interaction-question-type-tag">
                {question.multiple
                  ? t('ai.interaction.multipleChoice')
                  : t('ai.interaction.singleChoice')}
              </span>
            </div>
            {completed?.answers?.[index]?.length ? (
              <div className="interaction-completed-answers">
                {completed.answers[index].map((answer, answerIndex) => (
                  <span key={answerIndex} className="interaction-answer-tag">
                    {answer}
                  </span>
                ))}
              </div>
            ) : null}
          </div>
        ))}
      </div>
    </div>
  )
}

/** 从有序结果内容取出规范答案 JSON：json 内容序列化，否则使用首段非空文本。 */
function resultJson(contents: ToolContent[]): string | null {
  for (const content of contents) {
    if (content.type === 'json') {
      return jsonContentText(content.value)
    }
    if (content.type === 'text' && content.text.trim()) {
      return content.text
    }
  }
  return null
}
