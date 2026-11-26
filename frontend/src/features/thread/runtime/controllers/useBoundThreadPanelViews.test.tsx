import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
  useBoundThreadPanelLabels,
  useBoundThreadPanelViews,
} from '@/features/thread/runtime/controllers/useBoundThreadPanelViews'
import type { ThreadEventRecord } from '@/features/thread/runtime/thread-timeline/thread-events'
import type { BoundThreadPanelController } from '@/features/thread/runtime/controllers/useBoundThreadPanelViews'
import { harnessService } from '@/shared/api/harness-service'
import { createBuiltinExecution } from '@/shared/api/contracts/ai-runtime'
import type {
  EnvironmentDTO,
  HarnessModelRequestDebugDTO,
} from '@/shared/api/generated/contracts'
import { testDaemonConfig } from '@/test-support/environment-test-fixtures'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: { getModelRequestDebug: vi.fn(), previewHistoricalRequest: vi.fn() },
}))

const DEBUG: HarnessModelRequestDebugDTO = {
  kind: 'NEXT_REQUEST_PREVIEW', generatedAt: 1790504400,
  model: { modelName: 'm', variant: 'default' },
  environmentName: null, systemInstruction: 'prompt', tools: [], skills: [], subagents: [],
  cacheControl: null, planningError: null,
  frozenInvocation: {
    kind: 'FROZEN_INVOCATION',
    generationId: 'generation-1',
    attempt: 1,
    requestJson: '{"model":"first"}',
  },
}

function boundController(): BoundThreadPanelController {
  return {
    bodyRef: { current: null }, sessionId: 'session', models: [],
    events: [{
      id: 'entry:a', entryId: 'a', source: 'entry', turnStartEntryId: null, turnNumber: 1,
      kind: 'TOOL_CALL', title: 'ASSISTANT', status: 'completed', summary: 'tool_call',
      createdAt: 1785232800000, details: [], rawJson: '{}', historicalPreviewEligible: true,
    }],
    thread: {
      threadId: 'thread', name: 'main', sessionId: 'session', headEntryId: 'head',
      parentThreadId: null, rootThreadId: 'thread', yoloEnabled: false,
      nextCommandSequence: '1', version: '1', status: 'RUNNING',
      executionControl: 'RUNNABLE', branchSettings: {
        agentName: 'agent', execution: createBuiltinExecution(DEBUG.model.modelName, DEBUG.model.variant), environmentName: null,
      }, createdAt: 1790504400, updatedAt: 1790504400,
    },
    activeAssistantOutput: {
      generationId: 'generation-1', turnStartEntryId: 'turn', requestHeadEntryId: 'head',
      status: 'READY', attempt: 1, sequence: '0', contents: [],
      error: null, resultEntryId: null, createdAt: 1790504400, updatedAt: 1790504400,
    },
  }
}

it('refreshes authoritative invocation, phase, version and planner settings without a working toggle', async () => {
  vi.mocked(harnessService.getModelRequestDebug).mockResolvedValue(DEBUG)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const initial = boundController()
  const { result, rerender } = renderHook(
    ({ controller }) => useBoundThreadPanelViews('thread', controller),
    { initialProps: { controller: initial }, wrapper: ({ children }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ) },
  )
  act(() => result.current.switchMode('debug'))
  await waitFor(() => expect(result.current.debug?.frozenInvocation).toEqual(DEBUG.frozenInvocation))
  const changes: BoundThreadPanelController[] = [
    { ...initial, activeAssistantOutput: { ...initial.activeAssistantOutput!, status: 'RUNNING' } },
    { ...initial, thread: { ...initial.thread!, status: 'IDLE' } },
    { ...initial, activeAssistantOutput: null },
    { ...initial, activeAssistantOutput: { ...initial.activeAssistantOutput!, generationId: 'generation-2' } },
    { ...initial, thread: { ...initial.thread!, version: '2' } },
    { ...initial, thread: { ...initial.thread!, branchSettings: { ...initial.thread!.branchSettings, agentName: 'other' } } },
  ]
  for (const controller of changes) {
    let finish!: (data: HarnessModelRequestDebugDTO) => void
    vi.mocked(harnessService.getModelRequestDebug).mockImplementationOnce(
      () => new Promise((resolve) => { finish = resolve }),
    )
    rerender({ controller })
    expect(result.current.debug).toBeNull()
    await act(async () => finish({ ...DEBUG, frozenInvocation: null }))
    await waitFor(() => expect(result.current.debug).not.toBeNull())
    expect(result.current.debug?.frozenInvocation).toBeNull()
  }
})

it('renders on-demand preview, inspector and history actions and clears history when closing', async () => {
  vi.mocked(harnessService.getModelRequestDebug).mockResolvedValue(DEBUG)
  vi.mocked(harnessService.previewHistoricalRequest).mockResolvedValue({
    kind: 'HISTORICAL_REQUEST_PREVIEW', providerType: 'OPENAI', modelName: 'm',
    bodyByteSize: 2, bodyJson: '{"history":true}', sourceHeadEntryId: 'head', generatedAt: 1790504400,
    notice: 'Preview is reconstructed from the current catalog, provider configuration and model selection against the recorded history before this output.',
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const controller = boundController()
  function Harness() {
    const views = useBoundThreadPanelViews('thread', controller)
    return <>
      {views.mode === 'debug' ? <button onClick={() => views.switchMode('conversation')}>关闭 Debug</button> : null}
      <button onClick={() => views.switchMode('debug')}>测试 Debug 命令</button>
      {views.mainView.debug}
    </>
  }
  render(<QueryClientProvider client={client}><Harness /></QueryClientProvider>)
  fireEvent.click(screen.getByRole('button', { name: '测试 Debug 命令' }))
  await waitFor(() => expect(screen.getByText('prompt')).toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: /查看当前调用冻结/ }))
  expect(screen.getByTestId('frozen-request-json')).toHaveTextContent('first')
  expect(screen.getByText(/READY 阶段即可读取，不证明请求已经发送/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '关闭检查器' }))
  fireEvent.click(screen.getByRole('option'))
  fireEvent.click(screen.getByTestId('historical-request-preview'))
  await waitFor(() => expect(screen.getByTestId('preview-request-body')).toHaveTextContent('history'))
  expect(screen.getByText(/按当前目录、提供商与模型定义重建/)).toBeInTheDocument()
  expect(screen.getByText('规划时刻记录')).toBeInTheDocument()
  expect(screen.queryByText('生成时间')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '关闭检查器' }))
  expect(screen.queryByTestId('preview-request-body')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '关闭事件详情' }))
  fireEvent.click(screen.getByRole('button', { name: '关闭 Debug' }))
  expect(screen.queryByTestId('preview-request-body')).toBeNull()
})

const READY_ENVIRONMENT: EnvironmentDTO = {
  id: '8d347585-fd47-46da-9e0b-66d61e0ff21b',
  name: 'dev-box',
  daemonConfig: testDaemonConfig(),
  registrationToken: null,
  status: 'READY',
  statusExpiresAt: null,
  ready: true,
  lastSeenAt: 1789776000000,
  capabilities: [],
  userName: 'dev-user',
  homeDirectory: '/home/dev',
  version: '1',
  createdAt: 1789776000000,
  updatedAt: 1789776000000,
}

describe('useBoundThreadPanelLabels', () => {
  /**
   * 测试意图：bound footer 只投影已生效 snapshot 的 environmentName；
   * 已知名称解析为环境的 UUID/READY，未知名称保留原名并标记 unavailable，null 才表示 none。
   */
  it('resolves the active branch environment name without losing unknown identities', () => {
    const { result, rerender } = renderHook(
      ({ environmentName }: { environmentName: string | null }) =>
        useBoundThreadPanelLabels([READY_ENVIRONMENT], {
          runtimeLabels: { environmentName, contextWindow: 128_000 },
          branchUsage: null,
        }),
      { initialProps: { environmentName: 'dev-box' as string | null } },
    )

    expect(result.current.environment).toEqual({
      environmentId: READY_ENVIRONMENT.id,
      environmentName: 'dev-box',
    })
    expect(result.current.environmentReady).toBe(true)

    rerender({ environmentName: 'deleted-box' })
    expect(result.current.environment).toEqual({
      environmentId: 'deleted-box',
      environmentName: 'deleted-box',
    })
    expect(result.current.environmentReady).toBe(false)

    rerender({ environmentName: null })
    expect(result.current.environment).toBeNull()
    expect(result.current.environmentReady).toBeUndefined()
  })
})

/** 换 Thread 时清掉 Debug 视图状态，同一 Thread 重渲染则保留。 */
describe('useBoundThreadPanelViews view identity', () => {
  const PREVIEW = {
    kind: 'HISTORICAL_REQUEST_PREVIEW' as const,
    providerType: 'OPENAI',
    modelName: 'MiniMax',
    bodyByteSize: 120,
    bodyJson: '{"messages":[{"role":"user","content":"draft"}]}',
    sourceHeadEntryId: 'entry-2',
    generatedAt: 1790485200,
  }

  function renderViews(initialProps: { threadId: string }) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const controller: BoundThreadPanelController = {
      bodyRef: { current: null },
      events: [] as ThreadEventRecord[],
      sessionId: 'session',
      thread: undefined,
      activeAssistantOutput: null,
      models: [],
    }
    return renderHook(
      ({ threadId }: { threadId: string }) => useBoundThreadPanelViews(threadId, controller),
      {
        initialProps,
        wrapper: ({ children }: { children: ReactNode }) => (
          <QueryClientProvider client={client}>{children}</QueryClientProvider>
        ),
      },
    )
  }

  /** 进入 Debug 并选中一份预览结果，模拟成功预览后的检查器状态。 */
  function openDebugPreview(result: { current: ReturnType<typeof useBoundThreadPanelViews> }) {
    act(() => result.current.switchMode('debug'))
    act(() => result.current.selectDebugInspector({ type: 'preview', preview: PREVIEW }))
  }

  it('resets mode, selection and inspector when the bound Thread changes', () => {
    const { result, rerender } = renderViews({ threadId: 'thread-a' })
    openDebugPreview(result)
    expect(result.current.mode).toBe('debug')
    expect(result.current.debugSelection).toEqual({ type: 'preview', preview: PREVIEW })

    rerender({ threadId: 'thread-b' })

    expect(result.current.mode).toBe('conversation')
    expect(result.current.selectedEventId).toBeNull()
    expect(result.current.debugSelection).toBeNull()
    expect(result.current.initialConversationScrollTop).toBeNull()
    expect(result.current.mainView.debug).toBeUndefined()
  })

  it('keeps the debug view while the identity is unchanged', () => {
    const { result, rerender } = renderViews({ threadId: 'thread-a' })
    openDebugPreview(result)

    rerender({ threadId: 'thread-a' })

    expect(result.current.mode).toBe('debug')
    expect(result.current.debugSelection).toEqual({ type: 'preview', preview: PREVIEW })
  })
})
