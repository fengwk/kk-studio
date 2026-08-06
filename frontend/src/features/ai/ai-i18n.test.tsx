import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { AgentSelectionModal } from '@/features/ai/chat/SelectionListModal'
import { CreateChatModal } from '@/features/ai/chat/CreateChatModal'
import { EnvironmentsPage } from '@/features/ai/environment/EnvironmentsPage'
import { ResourceEditorModal } from '@/features/ai/catalog/AiConsoleResourceEditorModal'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-agent-draft-codec'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import { emptyProviderDraft } from '@/features/ai/catalog/ai-provider-draft-codec'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
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
    tools: [],
    skills: [],
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
    navigation: [
      {
        id: 'ai.nav.environments',
        label: 'Environment',
        labelKey: 'ai.nav.environments',
        path: 'environments',
      },
    ],
  })
  return host
}

function renderWithWorkbench(ui: ReactNode, path: string) {
  return render(
    <ExtensionHostProvider host={createHost()}>
      <MemoryRouter initialEntries={[`/${path}`]}>{ui}</MemoryRouter>
    </ExtensionHostProvider>,
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
  it('switches the Environment navigation label without remounting', () => {
    act(() => setLocale('en-US'))
    renderWithWorkbench(<NavigationSlot />, 'environments')

    expect(screen.getByRole('link', { name: 'Environment' })).toBeInTheDocument()

    act(() => setLocale('zh-CN'))
    expect(screen.getByRole('link', { name: '环境' })).toBeInTheDocument()
  })

  it('switches the Chat creation flow labels live', () => {
    act(() => setLocale('en-US'))
    render(
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
      />,
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

  it('switches the Agent selection modal labels live', () => {
    act(() => setLocale('en-US'))
    render(
      <AgentSelectionModal
        open
        agents={[agent]}
        loading={false}
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

    expect(await screen.findByText('There are no live Environments')).toBeInTheDocument()

    act(() => setLocale('zh-CN'))
    expect(screen.getByText('当前没有 live Environment')).toBeInTheDocument()
  })
})
