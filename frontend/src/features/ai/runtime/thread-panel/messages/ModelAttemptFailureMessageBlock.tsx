import { useEffect, useState } from 'react'
import { ThinkingBlock } from '@/features/ai/runtime/thread-panel/messages/ThinkingBlock'
import { retryCountdownSeconds } from '@/features/ai/runtime/thread-panel/messages/retry-countdown'
import type {
  DialogueTimestamp,
  ModelAttemptFailureDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import { CopyButton } from '@/shared/ui/markdown/CodeBlock'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import { useI18n } from '@/shared/i18n'

export function ModelAttemptFailureMessageBlock({
  message,
}: {
  message: ModelAttemptFailureDialogueMessage
}) {
  const { t } = useI18n()
  const text = message.text
  const hasText = text.length > 0
  const terminal = message.nextAttempt == null
  const liveRetry = message.modelInvocationId != null
  const retry = useRetryCountdown(liveRetry ? message.retryAt : null)
  const errorCode = message.errorCode.trim()

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
      <ThinkingBlock thinking={message.thinking} streaming={false} />
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
        {errorCode ? <code>{errorCode}</code> : null}
        <pre className="thread-error-raw">{message.errorMessage}</pre>
      </section>
    </div>
  )
}

function useRetryCountdown(retryAt: DialogueTimestamp): { remainingSeconds: number } {
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
