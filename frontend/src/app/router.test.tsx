import type { PropsWithChildren } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { Link, MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AppRouter } from '@/app/router'
import { ApplicationContributionsProvider } from '@/platform/extensions/ApplicationContributionsContext'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { makeApplicationContributions } from '@/test-support/application-contributions'

vi.mock('@/features/environment/ide/ChatIdeWorkspace', () => ({
  ChatIdeWorkspace: ({ active, children }: PropsWithChildren<{ active: boolean }>) => (
    <section data-testid="persistent-chat-host" hidden={!active}>
      <iframe title="retained-ide" />
      {children}
    </section>
  ),
}))
vi.mock('@/app/pages/InteractionsPage', () => ({
  InteractionsPage: () => <Link to="/chats/one">Return to Chat</Link>,
}))

describe('application route composition', () => {
  it('keeps the Chat host mounted even across the separate interactions route', async () => {
    // 覆盖实际 AppRouter 分支切换；仅平台内部路由的保活测试不能发现宿主被顶层分支卸载。
    const contributions = makeApplicationContributions({
      pages: [{
        id: 'chat', path: 'chats/:chatId',
        component: () => <Link to="/interactions">Interactions</Link>,
      }],
    })
    const sockets = new FakeWebSocketHarness()
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
          <ApplicationContributionsProvider contributions={contributions}>
            <MemoryRouter initialEntries={['/chats/one']}>
              <AppRouter />
            </MemoryRouter>
          </ApplicationContributionsProvider>
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )
    const host = screen.getByTestId('persistent-chat-host')
    const frame = screen.getByTitle('retained-ide')
    fireEvent.click(await screen.findByRole('link', { name: 'Interactions' }))
    expect(await screen.findByRole('link', { name: 'Return to Chat' })).toBeVisible()
    expect(screen.getByTestId('persistent-chat-host')).toBe(host)
    expect(host).not.toBeVisible()
    expect(screen.getByTitle('retained-ide')).toBe(frame)
    fireEvent.click(screen.getByRole('link', { name: 'Return to Chat' }))
    expect(await screen.findByRole('link', { name: 'Interactions' })).toBeVisible()
    expect(host).toBeVisible()
    expect(screen.getByTitle('retained-ide')).toBe(frame)
  })
})
