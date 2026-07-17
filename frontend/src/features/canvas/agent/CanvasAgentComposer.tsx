import { useEffect } from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { PlusIcon, SendIcon } from '@/features/canvas/icons'

/** Dock composer: add trigger + prompt + send. Mirrors ChatComposer boundary. */
export function CanvasAgentComposer({ menuId }: { menuId: string }) {
  const {
    state,
    agentPromptRef,
    dockAddRef,
    setAgentPrompt,
    sendAgent,
    toggleAddMenu,
  } = useCanvasRuntime()

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
    <div className="agent-dock" id="agentDock">
      <button
        className="dock-add"
        type="button"
        ref={dockAddRef}
        aria-label="添加内容"
        aria-expanded={state.addMenuOpen}
        aria-controls={menuId}
        onClick={toggleAddMenu}
      >
        <PlusIcon />
      </button>
      <textarea
        ref={agentPromptRef}
        rows={1}
        aria-label="向 Agent 描述任务"
        placeholder="告诉 Agent 下一步要完成什么…"
        value={state.agentPrompt}
        onChange={(event) => setAgentPrompt(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault()
            sendAgent()
          }
        }}
      />
      <button className="dock-send" type="button" aria-label="发送给 Agent" onClick={sendAgent}>
        <SendIcon />
      </button>
    </div>
  )
}
