import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AiNavigation } from '@/features/ai/extensions/AiNavigation'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { AgentSelectionPanel } from '@/features/ai/chat/SelectionPanel'
import { CreateChatModal } from '@/features/ai/chat/CreateChatModal'
import { EnvironmentsPage } from '@/features/ai/environment/EnvironmentsPage'
import { ResourceEditorModal } from '@/features/ai/catalog/AiConsoleResourceEditorModal'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-agent-draft-codec'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import { emptyProviderDraft } from '@/features/ai/catalog/ai-provider-draft-codec'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import { setLocale, translate } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
    getEnvironmentUpdate: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
  },
}))

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getRetryPolicy: vi.fn(),
    updateRetryPolicy: vi.fn(),
    getRealtimeStreamPolicy: vi.fn(),
    updateRealtimeStreamPolicy: vi.fn(),
  },
}))

const agent = {
  id: 'a1',
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'm1',
  variant: 'default',
  config: {
    inheritParentEnvironment: true,
    tools: [],
    skills: [],
    subagents: [],
  },
  version: '1',
  createTime: null,
  updateTime: null,
}

const defaultRetryPolicy = {
  maxRetries: 3,
  backoffStrategy: 'EXPONENTIAL' as const,
  baseDelayMillis: 2_000,
  maxDelayMillis: 60_000,
}

const defaultRealtimeStreamPolicy = { maxLength: 5_000 }

beforeEach(() => {
  vi.clearAllMocks()
  act(() => setLocale('zh-CN'))
  vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
  vi.mocked(harnessService.getRetryPolicy).mockResolvedValue(defaultRetryPolicy)
  vi.mocked(harnessService.updateRetryPolicy).mockResolvedValue(defaultRetryPolicy)
  vi.mocked(harnessService.getRealtimeStreamPolicy).mockResolvedValue(defaultRealtimeStreamPolicy)
  vi.mocked(harnessService.updateRealtimeStreamPolicy).mockResolvedValue(defaultRealtimeStreamPolicy)
})

afterEach(() => {
  act(() => setLocale('zh-CN'))
})

function createHost() {
  const host = new ExtensionHost()
  host.register({
    id: 'test.ai',
    pages: [
      {
        id: 'ai.nav.environments',
        component: () => null,
        path: 'environments',
        navGroup: 'ai',
        navItem: {
          label: 'Environment',
          labelKey: 'ai.nav.environments',
        },
      },
    ],
  })
  return host
}

function renderWithWorkbench(ui: ReactNode, path: string) {
  // 部分受测页面（如 EnvironmentsPage）依赖唯一应用事件连接：统一注入替身 WebSocket 并保持同一 Provider 契约。
  const sockets = new FakeWebSocketHarness()
  return render(
    <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
      <ExtensionHostProvider host={createHost()}>
        <MemoryRouter initialEntries={[`/${path}`]}>{ui}</MemoryRouter>
      </ExtensionHostProvider>
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

  it('switches the Environment navigation label without remounting', () => {
    act(() => setLocale('en-US'))
    renderWithWorkbench(<AiNavigation />, 'environments')

    expect(screen.getByRole('link', { name: 'Environment' })).toBeInTheDocument()

    act(() => setLocale('zh-CN'))
    expect(screen.getByRole('link', { name: '环境' })).toBeInTheDocument()
  })

  it('switches the Chat creation flow labels live', () => {
    act(() => setLocale('en-US'))
    render(
      <QueryClientProvider client={new QueryClient()}>
        <CreateChatModal
          open
          agents={[agent]}
          selectedAgentName=""
          title=""
          pending={false}
          onClose={() => undefined}
          onSelectAgent={() => undefined}
          onTitleChange={() => undefined}
          onSubmit={(event) => event.preventDefault()}
        />
      </QueryClientProvider>,
    )

    expect(screen.getByRole('form', { name: 'Create Chat' })).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Chat name (duplicates allowed)')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument()

    act(() => setLocale('zh-CN'))
    expect(screen.getByRole('form', { name: '新建 Chat' })).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Chat 名称（可重名）')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认创建' })).toBeInTheDocument()
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
        agentDraft={emptyAgentDraft()}
        pending={false}
        onClose={() => undefined}
        onProviderDraftChange={() => undefined}
        onModelDraftChange={() => undefined}
        onAgentDraftChange={() => undefined}
        onSubmit={(event) => event.preventDefault()}
      />,
    )

    expect(screen.getByRole('form', { name: 'Create Provider' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument()

    act(() => setLocale('zh-CN'))
    expect(screen.getByRole('form', { name: '新建 Provider' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认创建' })).toBeInTheDocument()
  })

  it('switches the Agent selection panel labels live', () => {
    act(() => setLocale('en-US'))
    render(
      <AgentSelectionPanel
        agents={[agent]}
        onSelect={() => undefined}
        onClose={() => undefined}
      />,
    )

    expect(screen.getByRole('heading', { name: 'Select Agent' })).toBeInTheDocument()

    act(() => setLocale('zh-CN'))
    expect(screen.getByRole('heading', { name: '选择 Agent' })).toBeInTheDocument()
  })

  it('switches Environment empty-state labels live', async () => {
    act(() => setLocale('en-US'))
    renderPage(<EnvironmentsPage />, 'environments')

    expect(await screen.findByRole('button', { name: 'Create Environment' })).toBeInTheDocument()
    expect(screen.getByText('Register execution environment and capabilities')).toBeInTheDocument()

    act(() => setLocale('zh-CN'))
    expect(screen.getByRole('button', { name: '创建环境' })).toBeInTheDocument()
    expect(screen.getByText('注册执行环境与运行时能力')).toBeInTheDocument()
  })
})
