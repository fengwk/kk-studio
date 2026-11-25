import { fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { environmentService } from '@/features/environment/environment-service'
import { IdeWorkspaceProvider } from './IdeWorkspaceProvider'
import { useIdeWorkspace } from './ide-workspace-context'
import { saveIdeSession } from './ide-session-state'
import { readIdeView } from './environment-ide-service'

vi.mock('@/features/environment/environment-service', () => ({ environmentService: { listEnvironmentSummaries: vi.fn() } }))
vi.mock('./environment-ide-service', () => ({ readIdeView: vi.fn() }))

const ENV = 'a1000000-0000-4000-8000-000000000001'
const VIEW = 'c1000000-0000-4000-8000-000000000001'
const IN_LAYOUT = 'c1000000-0000-4000-8000-000000000002'

it('prefers the layout view over the recovered view, without claiming either before placement', async () => {
  const result = vi.fn()
  function Probe() {
    const { resolveTarget, environments } = useIdeWorkspace()
    return environments.length > 0 && <button onClick={() => {
      result(resolveTarget(ENV), resolveTarget(ENV, IN_LAYOUT))
    }}>Resolve</button>
  }
  const data = new Map<string, string>()
  const storage = { sessionStorage: { getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value) } } }
  expect(saveIdeSession('b1000000-0000-4000-8000-000000000001', {
    [VIEW]: { environmentId: ENV, viewId: VIEW, daemonInstanceId: 'd1000000-0000-4000-8000-000000000001', bindingEpoch: '1' },
  }, storage)).toBe(true)
  vi.mocked(environmentService.listEnvironmentSummaries).mockResolvedValue([{ id: ENV, name: 'Dev', status: 'READY', ready: true, capabilities: [] }])
  const sockets = new FakeWebSocketHarness()
  render(<QueryClientProvider client={new QueryClient()}>
    <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
      <IdeWorkspaceProvider storage={storage}><Probe /></IdeWorkspaceProvider>
    </ApplicationEventProvider>
  </QueryClientProvider>)
  fireEvent.click(await screen.findByRole('button', { name: 'Resolve' }))
  expect(result).toHaveBeenCalledWith({ kind: 'vscode', environmentId: ENV, viewId: VIEW },
    { kind: 'vscode', environmentId: ENV, viewId: IN_LAYOUT })
  expect(readIdeView).not.toHaveBeenCalled()
})
