import { useEffect, useState } from 'react'
import { ThinkingBlock } from '@/features/thread/runtime/thread-panel/messages/ThinkingBlock'
import { retryCountdownSeconds } from '@/features/thread/runtime/thread-panel/messages/retry-countdown'
import { summarizeModelFailure } from '@/features/thread/runtime/thread-timeline/model-failure-summary'
import type {
  ModelAttemptFailureDialogueMessage,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { CopyButton } from '@/shared/ui/markdown/CodeBlock'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import { useI18n } from '@/shared/i18n'
import './message-blocks.css'

/**
 * 模型请求失败卡：主文案是失败类别的本地化说明加供应商错误信息，HTTP 状态作为辅助信息，
 * 完整原始错误文本放入默认折叠的详情并可复制；partial 正文与思考仍按普通消息展示。
 */
export function ModelAttemptFailureMessageBlock({
  message,
}: {
  message: ModelAttemptFailureDialogueMessage
}) {
  const { t } = useI18n()
  const text = message.text
  const hasText = text.length > 0
  const terminal = message.nextAttempt == null
  const liveRetry = message.generationId != null
  const retry = useRetryCountdown(liveRetry ? message.retryAt : null)
  const errorCode = message.errorCode.trim()
  const failure = summarizeModelFailure(message.errorCode, message.errorMessage)
  const kindLabel = failure.kindKey == null ? null : t(failure.kindKey)

  return (
    <div className="thread-turn thread-turn-model-attempt-failure">
      <section className="thread-block thread-block-model-attempt-failure-status">
        <div className="thread-block-body">
          <strong className="thread-attempt-failure-title">
            {t('ai.runtime.message.modelRequestFailed')}
          </strong>
          <span className="thread-attempt-failure-number">
            {t('ai.runtime.message.attemptNumber', {
              attempt: message.attempt,
            })}
          </span>
          {!terminal ? (
            <span className="thread-attempt-failure-retry">
              {!liveRetry
                ? t('ai.runtime.message.retryScheduledHistory', {
                    nextAttempt: message.nextAttempt ?? message.attempt + 1,
                  })
                : retry.remainingSeconds > 0
                ? t('ai.runtime.message.retryScheduled', {
                    nextAttempt: message.nextAttempt ?? message.attempt + 1,
                    seconds: retry.remainingSeconds,
                  })
                : t('ai.runtime.message.retryingNow', {
                    nextAttempt: message.nextAttempt ?? message.attempt + 1,
                  })}
            </span>
          ) : null}
        </div>
      </section>
      <ThinkingBlock thinking={message.thinking} />
      {hasText ? (
        <section className="thread-block thread-block-assistant thread-assistant-shell">
          <CopyButton
            source={text}
            className="thread-assistant-copy"
            label={t('ai.runtime.message.copyAll')}
          />
          <div className="thread-block-body thread-assistant-text">
            <MarkdownRenderer content={text} />
          </div>
        </section>
      ) : null}
      <section className="thread-block thread-block-model-attempt-failure-error">
        <div className="thread-attempt-failure-summary">
          <div className="thread-attempt-failure-headline">
            {kindLabel == null ? null : (
              <strong className="thread-attempt-failure-kind">{kindLabel}</strong>
            )}
            {errorCode ? <code>{errorCode}</code> : null}
            {failure.httpStatus == null ? null : (
              <span className="thread-attempt-failure-http">
                {t('ai.runtime.message.modelFailureHttpStatus', {
                  status: failure.httpStatus,
                })}
              </span>
            )}
          </div>
          {failure.summary ? (
            <p className="thread-attempt-failure-detail">{failure.summary}</p>
          ) : null}
        </div>
        {failure.rawResponse == null ? null : (
          <details className="thread-attempt-failure-raw">
            <summary>{t('ai.runtime.message.modelFailureRawResponse')}</summary>
            <div className="thread-attempt-failure-raw-body">
              <CopyButton
                source={failure.rawResponse}
                className="thread-attempt-failure-copy"
                label={t('ai.runtime.message.modelFailureCopyRawResponse')}
              />
              <pre className="thread-error-raw">{failure.rawResponse}</pre>
            </div>
          </details>
        )}
      </section>
    </div>
  )
}

function useRetryCountdown(retryAt: number | null): { remainingSeconds: number } {
  const [now, setNow] = useState(() => Date.now())
  const remainingSeconds = retryCountdownSeconds(retryAt, now)

  useEffect(() => {
    if (remainingSeconds === 0) {
      return undefined
    }
    const current = Date.now()
    setNow(current)
    const timer = window.setTimeout(() => {
      setNow(Date.now())
    }, Math.max(1, Math.min(1000, 1000 - (current % 1000))))
    return () => {
      window.clearTimeout(timer)
    }
  }, [remainingSeconds, retryAt])

  return { remainingSeconds }
}
