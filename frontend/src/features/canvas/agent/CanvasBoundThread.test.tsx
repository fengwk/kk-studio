import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CanvasBoundThread } from '@/features/canvas/agent/CanvasBoundThread'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'

const THREAD_ID = '11111111-2222-4333-8444-555555555555'

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: () => () => undefined }
  return { fakeApplicationEvents: { useApplicationEvents: () => manager } }
})

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: fakeApplicationEvents.useApplicationEvents,
}))
vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadSnapshot: vi.fn(),
    getSystemPromptPreview: vi.fn(),
    enqueueCommands: vi.fn(),
    updateThreadHead: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
  },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listDirectories: vi.fn(),
  },
}))

const page = <T,>(results: T[]) => ({
  pageNumber: 1,
  pageSize: 50,
  totalCount: results.length,
  results,
})

function modelSelection(
  overrides: Partial<HarnessModelSelectionDTO> = {},
): HarnessModelSelectionDTO {
  return {
    providerName: 'minimax',
    modelName: 'MiniMax',
    variant: 'default',
    ...overrides,
  }
}

function branchSettings(
  overrides: Partial<HarnessBranchSettingsDTO> = {},
): HarnessBranchSettingsDTO {
  return {
    environment: null,
    agentName: 'assistant',
    model: modelSelection(),
    activeTools: [],
    ...overrides,
  }
}

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: THREAD_ID,
    sessionId: 's1',
    headEntryId: 'e-assistant',
    yoloEnabled: false,
    nextCommandSequence: '1',
    revision: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: branchSettings(),
    createTime: '2026-01-01T00:00:00Z',
    updateTime: '2026-01-02T00:00:00Z',
    ...overrides,
  }
}

function snapshot(
  currentThread: HarnessThreadDTO,
  extras: Partial<HarnessThreadSnapshotDTO> = {},
): HarnessThreadSnapshotDTO {
  return {
    revision: currentThread.revision,
    thread: currentThread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    ...extras,
  }
}

const agents = [
  {
    name: 'assistant',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    config: { tools: [], skills: [], subagents: [] },
    version: '0',
    createTime: null,
    updateTime: null,
  },
  {
    name: 'coder',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    config: { tools: ['web-search'], skills: [], subagents: [] },
    version: '0',
    createTime: null,
    updateTime: null,
  },
]

const modelEntry = {
  providerName: 'minimax',
  name: 'MiniMax',
  description: null,
  config: {
    limit: { context: 128000, output: 8192 },
    abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
    pricing: {
      currency: 'USD',
      pricingTier: 'default',
      serviceTier: 'default',
      serviceTierMultiplier: 1,
      version: 'v1',
      inputPerMillionTokens: 0,
      outputPerMillionTokens: 0,
      cacheReadPerMillionTokens: 0,
      cacheWritePerMillionTokens: 0,
      cacheWriteLongPerMillionTokens: 0,
      reasoningPerMillionTokens: 0,
    },
    defaultVariant: 'default',
    variants: [{ id: 'default' }],
  },
  version: '0',
  createTime: null,
  updateTime: null,
}

function renderCanvasBound() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <CanvasBoundThread
        threadId={THREAD_ID}
        environments={[
          { name: 'local', ready: true, status: 'READY', lastSeen: null, tools: [], skills: [] },
        ]}
      />
    </QueryClientProvider>,
  )
}

describe('CanvasBoundThread scene', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue(page(agents))
    vi.mocked(agentService.listModels).mockResolvedValue(page([modelEntry]))
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread({})))
    vi.mocked(harnessService.getSystemPromptPreview).mockResolvedValue({ text: '' })
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([] as HarnessThreadCommandDTO[])
    vi.mocked(environmentService.listDirectories).mockResolvedValue({
      path: '.',
      displayPath: '.',
      parentPath: '.',
      truncated: false,
      gitBranch: null,
      entries: [],
    })
  })

  it('wires the shared draft editor into the canvas-bound composer and sends a minimal diff batch', async () => {
    const user = userEvent.setup()
    renderCanvasBound()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /coder/ }))

    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.type(composer, 'hello world')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.enqueueCommands).toHaveBeenCalledTimes(1))
    const [threadIdArg, batch] = vi.mocked(harnessService.enqueueCommands).mock.calls[0]!
    expect(threadIdArg).toBe(THREAD_ID)
    expect(batch.commands.map((command) => command.type)).toEqual([
      'SET_AGENT',
      'SET_ACTIVE_TOOLS',
      'USER_MESSAGE',
    ])
    const setAgent = batch.commands.find((command) => command.type === 'SET_AGENT')!
    expect(setAgent).toMatchObject({ agentName: 'coder' })
    const setTools = batch.commands.find((command) => command.type === 'SET_ACTIVE_TOOLS')!
    expect(setTools).toMatchObject({ activeTools: ['web-search'] })
    expect(harnessService.updateThreadHead).not.toHaveBeenCalled()
    // Agent picker 已关闭（scene 交互面板生命周期不受共享 draft 编辑影响）。
    expect(screen.queryByLabelText('选择 Agent')).not.toBeInTheDocument()
  })

  it('keeps thread/tree/new disabled in the canvas-bound command scene', async () => {
    const user = userEvent.setup()
    renderCanvasBound()
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    for (const command of ['tree', 'new', 'thread']) {
      await user.clear(composer)
      await user.keyboard(`/${command}`)
      const option = await screen.findByRole('option', { name: new RegExp(command) })
      expect(option).toBeDisabled()
    }

    // canvas-bound 场景 agent 仍可编辑：命令未禁用。
    await user.clear(composer)
    await user.keyboard('/agent')
    const agentOption = await screen.findByRole('option', { name: /^agent/ })
    expect(agentOption).toBeEnabled()
  })
})
