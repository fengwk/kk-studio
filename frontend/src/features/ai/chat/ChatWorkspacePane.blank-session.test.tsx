import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatWorkspacePane } from '@/features/ai/chat/ChatWorkspacePane'
import { BLANK_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import { agentService } from '@/shared/api/agent-service'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))
vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChatThreads: vi.fn(),
    createChatThread: vi.fn(),
    associateThread: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    listThreads: vi.fn(),
    createThread: vi.fn(),
    bootstrapThread: vi.fn(),
    submitThreadMessage: vi.fn(),
    createThreadRealtimeStream: vi.fn(),
  },
}))

class FakeEventSource {
  close = vi.fn()
  addEventListener = vi.fn()
}

function thread(overrides: Partial<HarnessThreadDTO>): HarnessThreadDTO {
  return {
    threadId: 't1',
    revision: '0',
    sessionId: null,
    sessionTitle: null,
    headEntryId: null,
    executionEpoch: 0,
    status: 'UNBOUND',
    inputSequence: 0,
    activeAgentDefinitionId: null,
    activeAgentName: null,
    activeEnvironmentName: null,
    modelId: null,
    variant: null,
    yoloEnabled: false,
    processing: false,
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

const agents = [
  {
    id: 'a1',
    name: 'assistant',
    description: null,
    systemPrompt: null,
    modelId: 'm1',
    variant: 'default',
    config: {
      tools: [],
      skills: [],
    },
    createTime: null,
    updateTime: null,
  },
]

function renderBlankPane(overrides?: {
  onThreadChange?: (threadId: string | null) => void
  onDefaultAgentChange?: (agentId: string) => Promise<void>
  onDefaultEnvironmentChange?: (environmentName: string | null) => Promise<void>
  defaultAgentId?: string
  defaultEnvironmentName?: string | null
}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const onThreadChange = overrides?.onThreadChange ?? vi.fn()
  const onDefaultAgentChange = overrides?.onDefaultAgentChange ?? vi.fn(async () => undefined)
  const onDefaultEnvironmentChange =
    overrides?.onDefaultEnvironmentChange ?? vi.fn(async () => undefined)
  render(
    <QueryClientProvider client={queryClient}>
      <ChatWorkspacePane
        chat={{
          id: 'chat-1',
          title: 'C',
          defaultAgentId: overrides?.defaultAgentId === undefined ? 'a1' : overrides.defaultAgentId,
          defaultEnvironmentName: overrides?.defaultEnvironmentName ?? null,
          version: '1',
          createTime: null,
          updateTime: null,
        }}
        agents={agents}
        environments={[
          { name: 'local', status: 'READY', lastSeen: null, tools: [], skills: [] },
          { name: 'connecting', status: 'CONNECTING', lastSeen: null, tools: [], skills: [] },
        ]}
        pane={{ id: 'pane-1', threadId: null }}
        focused
        sessionSort="recent"
        threadSort="recent"
        onFocus={() => undefined}
        onThreadChange={onThreadChange}
        onSessionSortChange={() => undefined}
        onThreadSortChange={() => undefined}
        onDefaultAgentChange={onDefaultAgentChange}
        onDefaultEnvironmentChange={onDefaultEnvironmentChange}
      />
    </QueryClientProvider>,
  )
  return { onThreadChange, onDefaultAgentChange, onDefaultEnvironmentChange }
}

describe('BlankComposerPane /thread and agent error handling', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: agents.length,
      results: agents,
    })
    vi.mocked(agentService.listModels).mockResolvedValue({ pageNumber: 1, pageSize: 50, totalCount: 0, results: [] })
    vi.mocked(agentService.listProviders).mockResolvedValue({ pageNumber: 1, pageSize: 50, totalCount: 0, results: [] })
    vi.mocked(harnessService.createThreadRealtimeStream).mockReturnValue(new FakeEventSource() as EventSource)
  })

  it('exposes full command table on blank panes with unsupported entries disabled', () => {
    expect(BLANK_PANE_COMMANDS.map((command) => command.id)).toEqual([
      'session',
      'thread',
      'agent',
      'environment',
      'model',
      'variant',
      'yolo',
      'tree',
      'stop',
      'new',
    ])
    // /thread only switches the pane, so it works before any Thread exists; /session and /tree
    // rebind the *current* Thread and therefore stay disabled on a blank pane.
    expect(BLANK_PANE_COMMANDS.filter((command) => !command.disabled).map((command) => command.id)).toEqual([
      'thread',
      'agent',
      'environment',
    ])
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'session')?.disabled).toBe(true)
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'tree')?.disabled).toBe(true)
    expect(BLANK_PANE_COMMANDS.find((command) => command.id === 'new')?.disabled).toBe(true)
  })

  it('sets and clears the Chat default Environment from the blank command and footer', async () => {
    const user = userEvent.setup()
    const onDefaultEnvironmentChange = vi.fn(async () => undefined)
    renderBlankPane({ onDefaultEnvironmentChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    expect(await screen.findByLabelText('选择 Environment')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'local' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'connecting' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'local' }))
    await waitFor(() => expect(onDefaultEnvironmentChange).toHaveBeenCalledWith('local'))

    await user.click(screen.getByRole('button', { name: '环境：（无）' }))
    expect(await screen.findByLabelText('选择 Environment')).toBeInTheDocument()
    await user.click(
      within(screen.getByLabelText('选择 Environment')).getByRole('button', {
        name: /（无）/,
      }),
    )
    await waitFor(() => expect(onDefaultEnvironmentChange).toHaveBeenLastCalledWith(null))
  })

  it('keeps the blank Environment selector open and shows the callback error', async () => {
    const user = userEvent.setup()
    const onDefaultEnvironmentChange = vi.fn(async () => {
      throw new Error('default environment update failed')
    })
    renderBlankPane({ onDefaultEnvironmentChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/environment{Enter}')
    await user.click(screen.getByRole('button', { name: 'local' }))

    await waitFor(() => {
      expect(screen.getByText('default environment update failed')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('选择 Environment')).toBeInTheDocument()
  })

  it('opens the global /thread picker and only switches the pane target', async () => {
    const user = userEvent.setup()
    const events: string[] = []
    vi.mocked(harnessService.listThreads).mockResolvedValue({
      items: [
        thread({
          threadId: 't-idle',
          sessionId: 's-idle',
          sessionTitle: 'Idle',
          headEntryId: 'h1',
          status: 'IDLE',
          executionEpoch: 2,
          updateTime: '2026-07-20T12:00:00Z',
          createTime: '2026-07-20T12:00:00Z',
        }),
        thread({
          threadId: 't-running',
          sessionId: 's-running',
          sessionTitle: 'Running',
          headEntryId: 'h2',
          status: 'RUNNING',
          executionEpoch: 3,
          processing: true,
          updateTime: '2026-07-19T12:00:00Z',
          createTime: '2026-07-19T12:00:00Z',
        }),
        thread({ threadId: 't-unbound', updateTime: '2026-07-18T12:00:00Z', createTime: '2026-07-18T12:00:00Z' }),
      ],
      nextCursor: null,
    })

    vi.mocked(chatService.associateThread).mockImplementation(async () => {
      events.push('associate')
    })
    const onThreadChange = vi.fn(() => {
      events.push('bind')
    })
    renderBlankPane({ onThreadChange })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')

    expect(await screen.findByText('选择 Thread')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '全局 Thread' }))
    // The API owns ordering; recent mode keeps the newer IDLE Thread first.
    const items = await screen.findAllByRole('button', { name: /^t-/ })
    expect(items[0]).toHaveTextContent('t-idle')
    expect(items[0]).toHaveTextContent('2026-07-20 12:00')
    // UNBOUND Threads remain selectable so /session or /tree can bind them later.
    expect(items.some((item) => item.textContent?.includes('UNBOUND'))).toBe(true)

    await user.click(screen.getByRole('button', { name: /t-running/ }))
    expect(onThreadChange).toHaveBeenCalledWith('t-running')
    expect(chatService.associateThread).toHaveBeenCalledWith('chat-1', 't-running')
    expect(events).toEqual(['associate', 'bind'])
    // Selecting a Thread never mutates it and never runs the first-send path.
    expect(chatService.createChatThread).not.toHaveBeenCalled()
    expect(harnessService.bootstrapThread).not.toHaveBeenCalled()
    expect(harnessService.submitThreadMessage).not.toHaveBeenCalled()
  })

  it('does not bind a global Thread when Chat association fails', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.listThreads).mockResolvedValue({
      items: [thread({
        threadId: 't-global',
        sessionId: 's-global',
        sessionTitle: 'Global',
        headEntryId: 'h-global',
        status: 'IDLE',
        executionEpoch: 1,
      })],
      nextCursor: null,
    })
    vi.mocked(chatService.associateThread).mockRejectedValue(new Error('association failed'))
    const { onThreadChange } = renderBlankPane()

    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    await user.click(screen.getByRole('button', { name: '全局 Thread' }))
    await user.click(await screen.findByRole('button', { name: /t-global/ }))

    await waitFor(() => expect(screen.getByText('association failed')).toBeInTheDocument())
    expect(onThreadChange).not.toHaveBeenCalled()
    expect(screen.getByText('选择 Thread')).toBeInTheDocument()
  })

  it('surfaces rejected default-agent update without unhandled rejection', async () => {
    const user = userEvent.setup()
    const onDefaultAgentChange = vi.fn(async () => {
      throw new Error('default agent update failed')
    })
    renderBlankPane({ defaultAgentId: 'missing', onDefaultAgentChange })

    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'hello need agent')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByText('选择 Agent')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /assistant/ }))

    await waitFor(() => {
      expect(screen.getByText('default agent update failed')).toBeInTheDocument()
    })
    expect(onDefaultAgentChange).toHaveBeenCalledWith('a1')
    expect(chatService.createChatThread).not.toHaveBeenCalled()
  })

  it('restores the draft and reports the failure when atomic Thread creation rejects', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.createChatThread).mockRejectedValue(
      new Error('unknown agent definition: a1'),
    )

    const { onThreadChange } = renderBlankPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'first message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(screen.getByText('unknown agent definition: a1')).toBeInTheDocument())
    expect(harnessService.submitThreadMessage).not.toHaveBeenCalled()
    // The pane stays blank so the user can retry after fixing the Agent.
    expect(onThreadChange).not.toHaveBeenCalled()
    expect(await screen.findByDisplayValue('first message')).toBeInTheDocument()
  })
})
