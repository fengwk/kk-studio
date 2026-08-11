import { useEffect, useRef } from 'react'
import { CanvasAgentMessage } from '@/features/canvas/agent/messages/CanvasAgentMessage'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { useI18n } from '@/shared/i18n'

/** Thread 容器：可滚动的普通 Chat 消息列表。 */
export function CanvasAgentThread() {
  const { state } = useCanvasRuntime()
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
      <div className="thread-messages" ref={messagesRef} aria-live="polite">
        {state.messages.map((message, index) => (
          <CanvasAgentMessage key={`${message.kind}-${index}`} message={message} />
        ))}
      </div>
    </section>
  )
}
