import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentsPage } from '@/features/environment/EnvironmentsPage'
import { ResourceEditorModal } from '@/features/catalog/AiConsoleResourceEditorModal'
import { emptyAgentDraft } from '@/features/catalog/ai-agent-draft-codec'
import { emptyModelGroupDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { emptyProviderDraft } from '@/features/catalog/ai-provider-draft-codec'
import { environmentService } from '@/features/environment/environment-service'
import { setLocale, translate } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'

vi.mock('@/features/environment/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
    getEnvironmentRuntime: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
  },
}))

beforeEach(() => {
  vi.clearAllMocks()
  act(() => setLocale('zh-CN'))
  vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
})

afterEach(() => {
  act(() => setLocale('zh-CN'))
})

function renderWithWorkbench(ui: ReactNode, path: string) {
  // 部分受测页面（如 EnvironmentsPage）依赖唯一应用事件连接：统一注入替身 WebSocket 并保持同一 Provider 契约。
  const sockets = new FakeWebSocketHarness()
  return render(
    <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
      <MemoryRouter initialEntries={[`/${path}`]}>{ui}</MemoryRouter>
    </ApplicationEventProvider>,
  )
}

function renderPage(ui: ReactNode, path: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return renderWithWorkbench(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
    path,
  )
}

describe('AI i18n live-switch contracts', () => {
  it('interpolates the rejected Agent name in both locales', () => {
    // 错误必须定位具体 Agent，不能把占位符原样暴露给用户。
    expect(translate('ai.runtime.action.agentUnresolvable', { selectedAgent: 'broken-agent' }))
      .toBe('Agent broken-agent 的模型或变体无法解析，请选择其他 Agent。')
    act(() => setLocale('en-US'))
    expect(translate('ai.runtime.action.agentUnresolvable', { selectedAgent: 'broken-agent' }))
      .toBe('The model or variant for Agent broken-agent cannot be resolved; pick another agent.')
  })


  it('switches Catalog resource modal labels live', () => {
    act(() => setLocale('en-US'))
    render(
      <ResourceEditorModal
        modal={{ kind: 'provider', mode: 'create' }}
        providers={[]}
        models={[]}
        providerDraft={emptyProviderDraft()}
        modelDraft={emptyModelDraft()}
        modelGroupDraft={emptyModelGroupDraft()}
        agentDraft={emptyAgentDraft()}
        pending={false}
        onClose={() => undefined}
        onProviderDraftChange={() => undefined}
        onModelDraftChange={() => undefined}
        onModelGroupDraftChange={() => undefined}
        onModelCreateKindChange={() => undefined}
        onAgentDraftChange={() => undefined}
        onSubmit={(event) => event.preventDefault()}
      />,
    )

    expect(screen.getByRole('form', { name: 'Create Provider' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument()

    act(() => setLocale('zh-CN'))
    expect(screen.getByRole('form', { name: '创建 提供商' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '创建' })).toBeInTheDocument()
  })

  it('switches Environment empty-state labels live', async () => {
    act(() => setLocale('en-US'))
    renderPage(<EnvironmentsPage />, 'environments')

    expect(await screen.findByRole('button', { name: 'New Environment' })).toBeInTheDocument()
    expect(screen.getByText('Register execution environment and capabilities')).toBeInTheDocument()

    act(() => setLocale('zh-CN'))
    expect(screen.getByRole('button', { name: '新建环境' })).toBeInTheDocument()
    expect(screen.getByText('注册执行环境与运行时能力')).toBeInTheDocument()
  })
})
