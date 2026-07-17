import { useEffect, useRef } from 'react'
import { CanvasAgentMessage } from '@/features/canvas/agent/messages/CanvasAgentMessage'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'

/** Thread container: context switcher + scrollable message list. */
export function CanvasAgentThread() {
  const {
    state,
    run,
    contextCount,
    contextDescription,
    setContextMode,
    resetDemo,
    collapseThread,
    runAction,
    consumeThreadScroll,
  } = useCanvasRuntime()

  const messagesRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!state.forceThreadScroll || !messagesRef.current) {
      return
    }
    messagesRef.current.scrollTop = messagesRef.current.scrollHeight
    consumeThreadScroll()
  }, [state.forceThreadScroll, state.messages, consumeThreadScroll])

  return (
    <section
      className="agent-thread"
      id="agentThread"
      hidden={!state.threadOpen}
      inert={!state.threadOpen}
      aria-hidden={!state.threadOpen}
      aria-label="Agent 消息与运行状态"
    >
      <div className="thread-head">
        <div className="context-switch" role="group" aria-label="Agent 上下文">
          <button
            type="button"
            className={state.contextMode === 'selection' ? 'active' : undefined}
            aria-pressed={state.contextMode === 'selection'}
            onClick={() => setContextMode('selection')}
          >
            当前选区
            {' '}
            <span>{contextCount}</span>
          </button>
          <button
            type="button"
            className={state.contextMode === 'whole' ? 'active' : undefined}
            aria-pressed={state.contextMode === 'whole'}
            onClick={() => setContextMode('whole')}
          >
            整张画布
          </button>
        </div>
        <div className="thread-actions">
          <button className="thread-reset" type="button" onClick={resetDemo}>重置</button>
          <button className="collapse-thread" type="button" aria-label="收起 Agent 消息" onClick={collapseThread}>⌄</button>
        </div>
      </div>
      <p className="context-description">{contextDescription}</p>
      <div className="thread-messages" ref={messagesRef} aria-live="polite">
        {state.messages.map((message, index) => (
          <CanvasAgentMessage
            key={`${message.kind}-${index}`}
            message={message}
            run={run}
            onRunAction={runAction}
          />
        ))}
      </div>
    </section>
  )
}
