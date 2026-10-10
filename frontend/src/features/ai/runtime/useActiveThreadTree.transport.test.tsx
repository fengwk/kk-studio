import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { SubagentTreePanel } from './SubagentTreePanel'
import { useActiveThreadTree } from './useActiveThreadTree'

const transport = vi.hoisted(() => vi.fn())
// 只替换 HTTP adapter：保留真实 Axios JSON 解析、信封解包与 harnessService 调用。
vi.mock('axios', async (importOriginal) => {
  const actual = await importOriginal<typeof import('axios')>()
  return {
    ...actual,
    default: {
      ...actual.default,
      create: (config: import('axios').CreateAxiosDefaults) =>
        actual.default.create({ ...config, adapter: transport }),
    },
  }
})

const ROOT = '00000000-0000-4000-8000-000000000001'
const CHILD = '00000000-0000-4000-8000-000000000002'
function node(threadId: string, parentThreadId: string | null) {
  return {
    threadId, parentThreadId, name: 'main', agentName: 'Explorer',
    updateTime: 1774958400.125,
    model: { providerName: 'p', modelName: 'm', variant: 'default' },
    processing: false, status: 'IDLE', turnCount: 3, toolCallCount: 5, outcome: 'COMPLETED',
  }
}
function reply(nodes: unknown[]) {
  transport.mockImplementation(async (config) => ({
    config, status: 200, statusText: 'OK', headers: { 'content-type': 'application/json' },
    data: JSON.stringify({ status: 200, code: 'OK', message: '', data: nodes }),
  }))
}
function Panel() {
  return <SubagentTreePanel tree={useActiveThreadTree(CHILD)} currentThreadId={CHILD} />
}
function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const sockets = new FakeWebSocketHarness()
  render(
    <QueryClientProvider client={client}>
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        <MemoryRouter><Panel /></MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
  return { client, sockets }
}

describe('execution tree HTTP Instant contract', () => {
  beforeEach(() => transport.mockReset())

  it('passes JSON epoch seconds through the real service and subscribes to the actual root', async () => {
    reply([node(ROOT, null), node(CHILD, ROOT)])
    const { client, sockets } = renderPanel()
    expect(await screen.findByRole('link', { name: /Explorer/ })).toHaveTextContent('turns: 3 · tools: 5')
    expect(transport.mock.calls[0][0]).toMatchObject({
      url: `/harness/threads/${CHILD}/tree`, method: 'get', baseURL: '/api',
    })
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual([node(ROOT, null), node(CHILD, ROOT)])
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toEqual([
      { version: 2, type: 'subscribe', resource: { kind: 'tree', id: ROOT } },
    ]))
  })

  it.each([
    ['empty', []],
    ['foreign', [node(ROOT, null)]],
    ['missing time', [node(ROOT, null), { ...node(CHILD, ROOT), updateTime: undefined }]],
    ['null time', [node(ROOT, null), { ...node(CHILD, ROOT), updateTime: null }]],
    ['array time', [node(ROOT, null), { ...node(CHILD, ROOT), updateTime: [2026, 3, 31] }]],
  ])('rejects %s HTTP tree without caching or subscribing', async (_, nodes) => {
    reply(nodes as unknown[])
    const { client, sockets } = renderPanel()
    await screen.findByRole('alert')
    expect(client.getQueryData(['threads', 'tree', CHILD])).toBeUndefined()
    expect(client.getQueryState(['threads', 'tree', CHILD])?.status).toBe('error')
    const socket = sockets.openLatest()
    await act(async () => { await Promise.resolve() })
    expect(socket.sentMessages()).toEqual([])
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('keeps the valid cached tree and root subscription when a refresh returns a foreign tree', async () => {
    const valid = [node(ROOT, null), node(CHILD, ROOT)]
    reply(valid)
    const { client, sockets } = renderPanel()
    await screen.findByRole('link')
    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toHaveLength(1))
    reply([node('foreign-root', null)])
    await act(async () => { await client.invalidateQueries({ queryKey: ['threads', 'tree', CHILD] }) })
    await screen.findByRole('alert')
    expect(client.getQueryData(['threads', 'tree', CHILD])).toEqual(valid)
    expect(screen.getByRole('link')).toBeInTheDocument()
    expect(socket.sentMessages()).toEqual([
      { version: 2, type: 'subscribe', resource: { kind: 'tree', id: ROOT } },
    ])
  })
})
