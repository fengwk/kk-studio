import { useEffect } from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { SendIcon } from '@/features/canvas/icons'
import { useI18n } from '@/shared/i18n'

/** Chat panel prompt 组合器；add launcher 独立位于左侧功能轨。 */
export function CanvasAgentComposer() {
  const {
    state,
    agentPromptRef,
    setAgentPrompt,
    sendAgent,
  } = useCanvasRuntime()
  const { t } = useI18n()

  useEffect(() => {
    const el = agentPromptRef.current
    if (!el) {
      return
    }
    const minimumHeight = 37
    const maximumHeight = 104
    el.style.height = 'auto'
    const measuredHeight = el.scrollHeight || minimumHeight
    const nextHeight = Math.max(minimumHeight, Math.min(measuredHeight, maximumHeight))
    el.style.height = `${nextHeight}px`
    el.style.overflowY = measuredHeight > maximumHeight ? 'auto' : 'hidden'
  }, [agentPromptRef, state.agentPrompt])

  return (
    <div className="agent-composer panel" id="agentDock">
      <textarea
        ref={agentPromptRef}
        rows={1}
        aria-label={t('canvas.agent.prompt.ariaLabel')}
        placeholder={t('canvas.agent.prompt.placeholder')}
        value={state.agentPrompt}
        onChange={(event) => setAgentPrompt(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault()
            sendAgent()
          }
        }}
      />
      <button className="dock-send" type="button" aria-label={t('canvas.agent.send')} onClick={sendAgent}>
        <SendIcon />
      </button>
      {Object.entries(state.uploadProgress).length > 0 ? (
        <div className="upload-progress-list" aria-live="polite">
          {Object.entries(state.uploadProgress).map(([name, progress]) => (
            <span key={name}>
              {name.split(':')[0]}
              {' '}
              {Math.round(progress * 100)}
              %
            </span>
          ))}
        </div>
      ) : null}
    </div>
  )
}
