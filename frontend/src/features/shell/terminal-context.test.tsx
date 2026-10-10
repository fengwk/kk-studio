/**
 * TerminalProvider / useTerminal / useOptionalTerminal 测试。
 *
 * 借用 useApplicationEvents（mock 为同一 fake manager），验证单一连接、StrictMode
 * effect start/stop 不重复 OPEN、显式 hide 才恢复焦点。
 */

import { StrictMode, useEffect } from 'react'
import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApplicationEventConnectionStatus } from '@/shared/app-events'
import type { TerminalCommand } from '@/features/shell/terminal-control-codec'
import {
  TerminalProvider,
  useOptionalTerminal,
  useTerminal,
  type TerminalApi,
} from '@/features/shell/terminal-context'

const ENV = 'aaaaaaaa-0000-4000-8000-000000000001'

const { fakeManager } = vi.hoisted(() => {
  const manager = {
    status: 'open' as ApplicationEventConnectionStatus,
    sent: [] as TerminalCommand[],
    subscribeCalls: 0,
    getStatus(): ApplicationEventConnectionStatus {
      return manager.status
    },
    sendTerminal(command: TerminalCommand): boolean {
      manager.sent.push(command)
      return true
    },
    subscribeTerminal(): () => void {
      manager.subscribeCalls += 1
      return () => undefined
    },
  }
  return { fakeManager: manager }
})

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: () => fakeManager,
}))

const captured: { current: TerminalApi | null } = { current: null }
const capturedShow: { current: TerminalApi | null } = { current: null }
const capturedHide: { current: TerminalApi | null } = { current: null }

function Probe() {
  const api = useTerminal()
  useEffect(() => {
    captured.current = api
  })
  return <span data-testid="visible">{String(api.snapshot.visible)}</span>
}

function ShowConsumer() {
  const api = useTerminal()
  useEffect(() => {
    capturedShow.current = api
  })
  return null
}

function HideConsumer() {
  const api = useTerminal()
  useEffect(() => {
    capturedHide.current = api
  })
  return null
}

function OptionalProbe() {
  const optional = useOptionalTerminal()
  return <span data-testid="optional">{String(optional !== null)}</span>
}

describe('TerminalProvider', () => {
  beforeEach(() => {
    fakeManager.sent.length = 0
    fakeManager.subscribeCalls = 0
    captured.current = null
    capturedShow.current = null
    capturedHide.current = null
  })

  afterEach(() => {
    captured.current = null
    capturedShow.current = null
    capturedHide.current = null
  })

  it('useTerminal 在 Provider 外抛出，useOptionalTerminal 返回 null', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    expect(() => render(<Probe />)).toThrow('TerminalProvider is required')
    errorSpy.mockRestore()
    render(<OptionalProbe />)
    expect(screen.getByTestId('optional')).toHaveTextContent('false')
  })

  it('useOptionalTerminal 在 Provider 内返回可用 api', () => {
    render(
      <TerminalProvider>
        <OptionalProbe />
      </TerminalProvider>,
    )
    expect(screen.getByTestId('optional')).toHaveTextContent('true')
  })

  it('展开/折叠驱动控制器，且不建立第二条连接', () => {
    render(
      <TerminalProvider>
        <Probe />
      </TerminalProvider>,
    )
    expect(screen.getByTestId('visible')).toHaveTextContent('false')
    expect(fakeManager.subscribeCalls).toBe(1)
    act(() => captured.current!.show(ENV))
    expect(fakeManager.sent.map((command) => command.type)).toEqual(['OPEN'])
    expect(captured.current!.snapshot.visible).toBe(true)
    act(() => captured.current!.hide())
    expect(captured.current!.snapshot.visible).toBe(false)
  })

  it('StrictMode 的 effect start-stop-start 不发送命令，也不重复 OPEN', () => {
    render(
      <StrictMode>
        <TerminalProvider>
          <Probe />
        </TerminalProvider>
      </StrictMode>,
    )
    // 未显式展开前无任何命令副作用。
    expect(fakeManager.sent).toHaveLength(0)
    act(() => captured.current!.show(ENV))
    expect(fakeManager.sent.filter((command) => command.type === 'OPEN')).toHaveLength(1)
  })

  it('只有显式 hide 才把焦点还给触发元素', () => {
    render(
      <div>
        <button data-testid="trigger" type="button">
          open
        </button>
        <TerminalProvider>
          <Probe />
        </TerminalProvider>
      </div>,
    )
    const trigger = screen.getByTestId('trigger')
    trigger.focus()
    expect(document.activeElement).toBe(trigger)
    act(() => captured.current!.show(ENV))
    // 展开后焦点可转移到面板内元素（此处模拟为 blur）。
    trigger.blur()
    expect(document.activeElement).not.toBe(trigger)
    act(() => captured.current!.hide())
    expect(document.activeElement).toBe(trigger)
  })

  it('多个消费者共享同一触发元素引用：由另一消费者 hide 也能恢复焦点', () => {
    render(
      <div>
        <button data-testid="trigger" type="button">
          open
        </button>
        <TerminalProvider>
          <ShowConsumer />
          <HideConsumer />
        </TerminalProvider>
      </div>,
    )
    const trigger = screen.getByTestId('trigger')
    trigger.focus()
    act(() => capturedShow.current!.show(ENV))
    trigger.blur()
    expect(document.activeElement).not.toBe(trigger)
    act(() => capturedHide.current!.hide())
    expect(document.activeElement).toBe(trigger)
  })

  it('触发元素卸载后 hide 不再抢焦点', () => {
    render(
      <div>
        <button data-testid="trigger" type="button">
          open
        </button>
        <TerminalProvider>
          <Probe />
        </TerminalProvider>
      </div>,
    )
    const trigger = screen.getByTestId('trigger')
    trigger.focus()
    act(() => captured.current!.show(ENV))
    act(() => {
      trigger.remove()
    })
    act(() => captured.current!.hide())
    expect(document.activeElement).not.toBe(trigger)
  })
})
