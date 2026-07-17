import { useEffect, useId, useRef, type KeyboardEvent, type RefObject } from 'react'
import { ADD_MENU_ITEMS, GENERATION_PROFILES, RUN_STEPS } from '@/features/canvas/data'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { PlusIcon, SendIcon } from '@/features/canvas/icons'

export function CanvasAgentDock({
  dockWrapRef,
}: {
  dockWrapRef: RefObject<HTMLDivElement | null>
}) {
  const {
    state,
    run,
    contextCount,
    contextDescription,
    agentPromptRef,
    dockAddRef,
    setAgentPrompt,
    sendAgent,
    toggleAddMenu,
    closeAddMenu,
    setAddMenuIndex,
    handleAddAction,
    setContextMode,
    resetDemo,
    collapseThread,
    runAction,
    consumeThreadScroll,
  } = useCanvasRuntime()

  const menuId = useId()
  const messagesRef = useRef<HTMLDivElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const menuButtons = ADD_MENU_ITEMS

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

  useEffect(() => {
    if (!state.forceThreadScroll || !messagesRef.current) {
      return
    }
    messagesRef.current.scrollTop = messagesRef.current.scrollHeight
    consumeThreadScroll()
  }, [state.forceThreadScroll, state.messages, consumeThreadScroll])

  useEffect(() => {
    if (!state.addMenuOpen || !menuRef.current) {
      return
    }
    const action = menuButtons[state.addMenuIndex]?.action
    if (!action) {
      return
    }
    const button = menuRef.current.querySelector<HTMLButtonElement>(`[data-add-action="${action}"]`)
    button?.focus()
  }, [state.addMenuOpen, state.addMenuIndex, menuButtons])

  function handleMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const count = menuButtons.length
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setAddMenuIndex((state.addMenuIndex + 1) % count)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setAddMenuIndex((state.addMenuIndex - 1 + count) % count)
    } else if (event.key === 'Home') {
      event.preventDefault()
      setAddMenuIndex(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      setAddMenuIndex(count - 1)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      closeAddMenu(true)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      const item = menuButtons[state.addMenuIndex]
      if (item) {
        handleAddAction(item.action)
      }
    }
  }

  return (
    <div className="agent-dock-wrap" id="agentDockWrap" ref={dockWrapRef}>
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
          {state.messages.map((message, index) => {
            if (message.kind === 'user') {
              return <div key={`user-${index}`} className="thread-message user">{message.text}</div>
            }
            if (message.kind === 'agent') {
              return <div key={`agent-${index}`} className="thread-message">{message.text}</div>
            }
            if (message.kind === 'generation') {
              const profile = GENERATION_PROFILES[message.mode]
              return (
                <div key={`generation-${index}`} className="thread-message run generation-message">
                  <strong>
                    {profile.label}
                    {' '}
                    ·
                    {' '}
                    {message.parameters}
                  </strong>
                  <div>{message.text}</div>
                </div>
              )
            }
            if (!run) {
              return null
            }
            const running = run.status === 'running'
            const paused = run.status === 'paused'
            const control = running ? '暂停' : paused ? '继续' : '重试'
            const action = running ? 'pause' : paused ? 'resume' : 'retry'
            return (
              <div key={`run-${index}`} className="thread-message run">
                <strong>
                  Agent Run ·
                  {' '}
                  {run.title}
                </strong>
                <div>
                  {RUN_STEPS.map((step, stepIndex) => {
                    const done = stepIndex < run.progress
                    const current = running && stepIndex === run.progress
                    return (
                      <div key={step} className={`run-step ${done ? 'done' : ''} ${current ? 'current' : ''}`}>
                        <b>{done ? '✓' : current ? '●' : '○'}</b>
                        {step}
                      </div>
                    )
                  })}
                </div>
                <small>
                  {running ? '运行中' : paused ? '已暂停' : '已完成'}
                  {' '}
                  ·
                  {' '}
                  {run.progress}
                  /
                  {run.total}
                </small>
                <div className="run-controls">
                  <button type="button" onClick={() => runAction(action)}>{control}</button>
                </div>
              </div>
            )
          })}
        </div>
      </section>

      <div
        className="add-menu"
        id={menuId}
        ref={menuRef}
        hidden={!state.addMenuOpen}
        inert={!state.addMenuOpen}
        aria-hidden={!state.addMenuOpen}
        role="menu"
        aria-label="添加内容"
        onKeyDown={handleMenuKeyDown}
      >
        {menuButtons.map((item, index) => (
          <button
            key={item.action}
            type="button"
            role="menuitem"
            data-add-action={item.action}
            tabIndex={state.addMenuOpen && index === state.addMenuIndex ? 0 : -1}
            onClick={() => handleAddAction(item.action)}
            onMouseEnter={() => setAddMenuIndex(index)}
          >
            <span className="add-menu-icon">{item.icon}</span>
            <span>{item.label}</span>
          </button>
        ))}
      </div>

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
    </div>
  )
}
