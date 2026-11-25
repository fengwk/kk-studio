import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { setLocale } from '@/shared/i18n'
import { WorkspaceInbox } from './WorkspaceInbox'

const id = (suffix: number) => `10000000-0000-4000-8000-${String(suffix).padStart(12, '0')}`
const spaceA = id(1), spaceB = id(2), rootA = id(11), rootB = id(12), bgRoot = id(13)

describe('WorkspaceInbox', () => {
  beforeEach(() => {
    setLocale('en-US')
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    setLocale('zh-CN')
    vi.unstubAllGlobals()
  })

  it('deduplicates roots, excludes ENVIRONMENT_WAIT, refreshes via WebSocket and opens target space thread', async () => {
    let phase = 1
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = new URL(String(input), 'http://localhost')
      if (url.pathname === '/api/harness/interactions') {
        expect(url.searchParams.has('rootThreadId')).toBe(false)
        if (phase === 1 && !url.searchParams.get('cursor')) {
          return new Response(JSON.stringify({
            items: [
              {
                type: 'ENVIRONMENT_WAIT',
                rootThreadId: id(99),
                environmentId: id(98),
                environmentName: 'dev',
                waitingCount: 1,
                createdAt: 99,
                owner: { kind: 'CHAT_SPACE', id: spaceA, title: 'Studio' },
              },
              {
                type: 'INPUT',
                toolInvocationId: id(21),
                threadId: rootA,
                rootThreadId: rootA,
                sessionId: id(31),
                toolCallId: 'c1',
                toolName: 'question',
                argumentsJson: '{}',
                createdAt: 10,
                owner: { kind: 'CHAT_SPACE', id: spaceA, title: 'Studio' },
              },
            ],
            nextCursor: 'next-page',
            total: 3,
          }), { headers: { 'Content-Type': 'application/json' } })
        }
        if (phase === 1 && url.searchParams.get('cursor') === 'next-page') {
          return new Response(JSON.stringify({
            items: [
              {
                type: 'APPROVAL',
                toolInvocationId: id(22),
                threadId: rootA,
                rootThreadId: rootA,
                sessionId: id(31),
                toolCallId: 'c2',
                toolName: 'bash',
                argumentsJson: '{}',
                queuedSameFileCount: 0,
                createdAt: 20,
                owner: { kind: 'CHAT_SPACE', id: spaceA, title: 'Studio' },
              },
              {
                type: 'INPUT',
                toolInvocationId: id(23),
                threadId: bgRoot,
                rootThreadId: bgRoot,
                sessionId: id(33),
                toolCallId: 'c3',
                toolName: 'question',
                argumentsJson: '{}',
                createdAt: 50,
                owner: { kind: 'SYSTEM', id: id(40), title: '' },
              },
            ],
            nextCursor: null,
            total: 3,
          }), { headers: { 'Content-Type': 'application/json' } })
        }
        return new Response(JSON.stringify({
          items: [
            {
              type: 'INPUT',
              toolInvocationId: id(24),
              threadId: rootB,
              rootThreadId: rootB,
              sessionId: id(32),
              toolCallId: 'c4',
              toolName: 'question',
              argumentsJson: '{}',
              createdAt: 60,
              owner: { kind: 'CHAT_SPACE', id: spaceB, title: 'Support' },
            },
          ],
          nextCursor: null,
          total: 1,
        }), { headers: { 'Content-Type': 'application/json' } })
      }
      if (url.pathname === `/api/chat-spaces/${spaceB}/threads/${rootB}`) {
        return new Response(JSON.stringify({
          sessionId: id(32),
          sessionTitle: 'Incident',
          folderId: id(50),
          title: 'Root cause thread',
          state: 'NEEDS_YOU',
          version: '0',
        }), { headers: { 'Content-Type': 'application/json' } })
      }
      return new Response('{}', { status: 404 })
    })

    const sockets = new FakeWebSocketHarness()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onOpen = vi.fn()
    const view = render(
      <QueryClientProvider client={client}>
        <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
          <WorkspaceInbox userId="user-1" onOpen={onOpen} />
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    const socket = sockets.openLatest()
    await waitFor(() => expect(socket.sentMessages()).toContainEqual({ type: 'subscribe', resource: { kind: 'interactions' } }))

    // Initial badge shows 2 deduplicated roots (rootA + bgRoot), excluding ENVIRONMENT_WAIT
    const trigger = await screen.findByRole('button', { name: 'Pending interactions' })
    await waitFor(() => expect(trigger).toHaveTextContent('2'))

    // WS changed refreshes the list
    phase = 2
    act(() => {
      socket.emitServer({ type: 'subscribed', resource: { kind: 'interactions' }, cursor: '0' })
      socket.emitServer({
        type: 'event',
        resource: { kind: 'interactions' },
        name: 'changed',
        data: { rootThreadId: rootB },
      })
    })

    await waitFor(() => expect(trigger).toHaveTextContent('1'))
    fireEvent.click(trigger)
    const item = await screen.findByRole('button', { name: /Root cause thread/ })
    expect(item).toHaveTextContent('Support')
    fireEvent.click(item)
    expect(onOpen).toHaveBeenCalledWith(spaceB, rootB)

    view.unmount()
  })
})
