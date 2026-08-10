import { useEffect, useRef } from 'react'
import { CanvasAgentMessage } from '@/features/canvas/agent/messages/CanvasAgentMessage'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { useI18n } from '@/shared/i18n'

/** Thread 容器：上下文切换器 + 可滚动的消息列表。 */
export function CanvasAgentThread() {
  const {
    state,
    contextCount,
    contextDescription,
    setContextMode,
    collapseThread,
  } = useCanvasRuntime()
  const { t } = useI18n()

  const messagesRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!state.threadOpen || !messagesRef.current) {
      return
    }
    messagesRef.current.scrollTop = messagesRef.current.scrollHeight
  }, [state.messages, state.threadOpen])

  return (
    <section
      className="agent-thread"
      id="agentThread"
      hidden={!state.threadOpen}
      inert={!state.threadOpen}
      aria-hidden={!state.threadOpen}
      aria-label={t('canvas.agent.thread.ariaLabel')}
    >
      <div className="thread-head">
        <div className="context-switch" role="group" aria-label={t('canvas.agent.context.ariaLabel')}>
          <button
            type="button"
            className={state.contextMode === 'selection' ? 'active' : undefined}
            aria-pressed={state.contextMode === 'selection'}
            onClick={() => setContextMode('selection')}
          >
            {t('canvas.agent.context.selection')}
            {' '}
            <span>{contextCount}</span>
          </button>
          <button
            type="button"
            className={state.contextMode === 'whole' ? 'active' : undefined}
            aria-pressed={state.contextMode === 'whole'}
            onClick={() => setContextMode('whole')}
          >
            {t('canvas.agent.context.whole')}
          </button>
        </div>
        <div className="thread-actions">
          <button className="collapse-thread" type="button" aria-label={t('canvas.agent.collapse')} onClick={collapseThread}>⌄</button>
        </div>
      </div>
      <p className="context-description">{contextDescription}</p>
      <div className="thread-messages" ref={messagesRef} aria-live="polite">
        {state.messages.map((message, index) => (
          <CanvasAgentMessage key={`${message.kind}-${index}`} message={message} />
        ))}
      </div>
    </section>
  )
}
