/**
 * /shell slash 接线测试：/shell 命令必须把已确认环境的 canonical id 交给终端控制器，
 * 绝不用环境 name 兜底，也不产生任何 Thread/Entry/命令批次写入。
 *
 * 真实 terminal-context 模块被 vi.mock 替换为 hoisted 替身；这里只关心 Hook 的调用契约。
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane/pane-target'
import { useRootThreadControl } from '@/features/ai/runtime/useRootThreadControl'
import { useThreadProjection } from '@/features/ai/runtime/useThreadProjection'
import type { AgentDefinitionDTO, AgentModelDTO } from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

const { fakeTerminal } = vi.hoisted(() => ({
  fakeTerminal: {
    controller: {},
    snapshot: {
      visible: true,
      activeEnvironmentId: null,
      connectionStatus: 'open',
      sessions: new Map(),
    },
    show: vi.fn(),
    hide: vi.fn(),
  },
}))

vi.mock('@/features/shell/terminal-context', () => ({
  useTerminal: () => fakeTerminal,
  useOptionalTerminal: () => fakeTerminal,
}))

vi.mock('@/shared/api/agent-service', () => ({
  agentService: { listAgents: vi.fn(), listModels: vi.fn(), listProviders: vi.fn() },
}))

const ENV_READY: EnvironmentCardDTO = {
  id: 'aaaaaaaa-0000-4000-8000-000000000001',
  name: 'prod-linux',
  status: 'READY',
  ready: true,
  statusExpiresAt: null,
  lastSeen: null,
  capabilities: [],
  version: '1',
  createTime: '2026-10-10T00:00:00Z',
  updateTime: '2026-10-10T00:00:00Z',
}

const AGENT: AgentDefinitionDTO = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  environmentId: null,
  config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}

const MODEL: AgentModelDTO = {
  providerName: 'minimax',
  name: 'MiniMax',
  modelId: 'MiniMax',
  description: null,
  config: {
    limit: { context: 128000, output: 8192 },
    abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
    pricing: {
      currency: 'USD',
      pricingTier: 'default',
      serviceTier: 'standard',
      serviceTierMultiplier: 1,
    },
    defaultVariant: 'default',
    variants: [{ id: 'default' }],
  },
  version: '0',
  createTime: null,
  updateTime: null,
}

function page<T>(results: T[]) {
  return { pageNumber: 1, pageSize: 50, totalCount: results.length, results }
}

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const sockets = new FakeWebSocketHarness()
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        {children}
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

function useTestControl() {
  const [target, setTarget] = useState<PaneTarget>({ kind: 'NEW_SESSION_DRAFT' })
  const projection = useThreadProjection('')
  return useRootThreadControl({
    owner: { type: 'CHAT', chatId: 'chat-test-1' },
    paneId: 'pane-test-1',
    target,
    setTarget,
    projection,
    agents: [AGENT],
    environments: [ENV_READY],
    defaults: {},
    focused: true,
  })
}

async function renderControlWithMaterializedDraft() {
  const rendered = renderHook(() => useTestControl(), { wrapper: createWrapper() })
  // 本地草稿由 agent + catalog model 物化；settings 出现即表示 draft 就绪。
  await waitFor(() => expect(rendered.result.current.composer.settings).toBeDefined())
  return rendered
}

describe('useRootThreadControl /shell command wiring', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue(page([AGENT]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([MODEL]))
  })

  it('passes the confirmed canonical environment id to terminal.show', async () => {
    const { result } = await renderControlWithMaterializedDraft()

    act(() => result.current.composer.settings!.onEnvironmentChange('prod-linux'))
    const shellCommand = result.current.composer.commands.find((command) => command.id === 'shell')!
    expect(shellCommand.disabled).toBe(false)
    act(() => result.current.composer.onCommand(shellCommand))

    expect(fakeTerminal.show).toHaveBeenCalledTimes(1)
    expect(fakeTerminal.show).toHaveBeenCalledWith(ENV_READY.id)
  })

  it('falls back to terminal.show() without arguments for an unknown environment name', async () => {
    const { result } = await renderControlWithMaterializedDraft()

    act(() => result.current.composer.settings!.onEnvironmentChange('non-existent-env'))
    const shellCommand = result.current.composer.commands.find((command) => command.id === 'shell')!
    act(() => result.current.composer.onCommand(shellCommand))

    expect(fakeTerminal.show).toHaveBeenCalledTimes(1)
    expect(fakeTerminal.show).toHaveBeenCalledWith()
    expect(fakeTerminal.show).not.toHaveBeenCalledWith('non-existent-env')
  })

  it('calls terminal.show() when the draft has no environment', async () => {
    const { result } = await renderControlWithMaterializedDraft()

    const shellCommand = result.current.composer.commands.find((command) => command.id === 'shell')!
    act(() => result.current.composer.onCommand(shellCommand))

    expect(fakeTerminal.show).toHaveBeenCalledTimes(1)
    expect(fakeTerminal.show).toHaveBeenCalledWith()
  })

  it('does not produce Thread, Entry, or command batch mutations when executing /shell', async () => {
    const acceptBatchSpy = vi.spyOn(harnessService, 'acceptCommandBatch')
    const acceptThreadBatchSpy = vi.spyOn(harnessService, 'acceptThreadCommandBatch')

    const { result } = await renderControlWithMaterializedDraft()
    act(() => result.current.composer.settings!.onEnvironmentChange('prod-linux'))
    const shellCommand = result.current.composer.commands.find((command) => command.id === 'shell')!
    act(() => result.current.composer.onCommand(shellCommand))

    expect(acceptBatchSpy).not.toHaveBeenCalled()
    expect(acceptThreadBatchSpy).not.toHaveBeenCalled()
  })
})
