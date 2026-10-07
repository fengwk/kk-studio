import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
  useBoundThreadPanelLabels,
  useBoundThreadPanelViews,
} from '@/features/ai/runtime/useBoundThreadPanelViews'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { BoundThreadPanelController } from '@/features/ai/runtime/useBoundThreadPanelViews'
import type { HarnessModelRequestDebugDTO } from '@/shared/api/contracts/ai-runtime'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: { getModelRequestDebug: vi.fn(), previewHistoricalRequest: vi.fn() },
}))

const DEBUG: HarnessModelRequestDebugDTO = {
  kind: 'NEXT_REQUEST_PREVIEW', generatedAt: 'now',
  model: { providerName: 'p', modelName: 'm', variant: 'default' },
  environmentName: null, systemInstruction: 'prompt', tools: [], skills: [], subagents: [],
  cacheControl: null, planningError: null,
  frozenInvocation: { kind: 'FROZEN_INVOCATION', requestJson: '{"model":"first"}' },
}

function boundController(): BoundThreadPanelController {
  return {
    bodyRef: { current: null }, sessionId: 'session', models: [],
    events: [{
      id: 'entry:a', entryId: 'a', source: 'entry', turnStartEntryId: null, turnNumber: 1,
      kind: 'TOOL_CALL', title: 'ASSISTANT', status: 'completed', summary: 'tool_call',
      createdAt: '2026-01-01', details: [], rawJson: '{}', historicalPreviewEligible: true,
    }],
    thread: {
      threadId: 'thread', name: 'main', sessionId: 'session', headEntryId: 'head',
      parentThreadId: null, yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
      nextCommandSequence: '1', version: '1', status: 'MODEL_READY', processing: true,
      executionControl: 'RUNNABLE', branchSettings: {
        agentName: 'agent', model: DEBUG.model, environmentName: null, goal: null,
      }, createTime: 'now', updateTime: 'now',
    },
    modelInvocation: {
      id: 'model-1', threadId: 'thread', turnStartEntryId: 'turn', requestHeadEntryId: 'head',
      status: 'READY', attempt: 1, streamCheckpointJson: null, resultJson: null,
      errorJson: null, resultEntryId: null, createTime: 'now', updateTime: 'now',
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
    { ...initial, modelInvocation: { ...initial.modelInvocation!, status: 'RUNNING' } },
    { ...initial, thread: { ...initial.thread!, status: 'TOOL_RUNNING' } },
    { ...initial, modelInvocation: null },
    { ...initial, modelInvocation: { ...initial.modelInvocation!, id: 'model-2' } },
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
    bodyByteSize: 2, bodyJson: '{"history":true}', sourceHeadEntryId: 'head', generatedAt: 'now',
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const controller = boundController()
  function Harness() {
    const views = useBoundThreadPanelViews('thread', controller, { onPreview: () => views.selectDebugInspector({
      type: 'preview', preview: {
        kind: 'DRAFT_REQUEST_PREVIEW', providerType: 'OPENAI', modelName: 'm',
        bodyByteSize: 2, bodyJson: '{}', sourceHeadEntryId: 'head', generatedAt: 'now',
      },
    }) })
    return <><button onClick={() => views.switchMode('debug')}>debug</button>{views.mainView.debug}</>
  }
  render(<QueryClientProvider client={client}><Harness /></QueryClientProvider>)
  fireEvent.click(screen.getByText('debug'))
  await waitFor(() => expect(screen.getByText('prompt')).toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: /查看当前调用冻结/ }))
  expect(screen.getByTestId('frozen-request-json')).toHaveTextContent('first')
  expect(screen.getByText(/READY 阶段即可读取，不证明请求已经发送/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '关闭检查器' }))
  fireEvent.click(screen.getByRole('option'))
  fireEvent.click(screen.getByTestId('historical-request-preview'))
  await waitFor(() => expect(screen.getByTestId('preview-request-body')).toHaveTextContent('history'))
  expect(screen.getByText(/按当前目录、Provider 与模型定义重建/)).toBeInTheDocument()
  expect(screen.getByText('输出记录时间（规划时刻）')).toBeInTheDocument()
  expect(screen.queryByText('生成时间')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '关闭检查器' }))
  expect(screen.queryByTestId('preview-request-body')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '关闭事件详情' }))
  fireEvent.click(screen.getByRole('button', { name: '预览当前草稿' }))
  expect(screen.getByTestId('preview-request-body')).toHaveTextContent('{}')
  expect(screen.getByText(/点击时的设置与草稿输入只读物化/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '返回会话' }))
  expect(screen.queryByTestId('preview-request-body')).toBeNull()
})

const READY_ENVIRONMENT: EnvironmentCardDTO = {
  id: '8d347585-fd47-46da-9e0b-66d61e0ff21b',
  name: 'dev-box',
  registrationToken: null,
  status: 'READY',
  ready: true,
  lastSeen: '2026-09-19T00:00:00.000Z',
  capabilities: [],
  userName: 'dev-user',
  homeDirectory: '/home/dev',
  version: '1',
  createTime: '2026-09-19T00:00:00.000Z',
  updateTime: '2026-09-19T00:00:00.000Z',
}

describe('useBoundThreadPanelLabels', () => {
  /**
   * 测试意图：bound footer 只投影已生效 snapshot 的 environmentName；
   * 已知名称解析为 Card UUID/READY，未知名称保留原名并标记 unavailable，null 才表示 none。
   */
  it('resolves the active branch environment name without losing unknown identities', () => {
    const { result, rerender } = renderHook(
      ({ environmentName }: { environmentName: string | null }) =>
        useBoundThreadPanelLabels([READY_ENVIRONMENT], {
          runtimeLabels: { environmentName, contextWindow: 128_000 },
          branchUsage: null,
        }),
      { initialProps: { environmentName: 'dev-box' } },
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

/**
 * 本地草稿没有 API threadId（都是 ""），因此视图状态必须按 viewKey 隔离；
 * 换绑目标时 Debug 模式、滚动位置与检查器选中一并作废，同身份则原样保留。
 */
describe('useBoundThreadPanelViews view identity', () => {
  const PREVIEW = {
    kind: 'DRAFT_REQUEST_PREVIEW' as const,
    providerType: 'OPENAI',
    modelName: 'MiniMax',
    bodyByteSize: 120,
    bodyJson: '{"messages":[{"role":"user","content":"draft"}]}',
    sourceHeadEntryId: 'entry-2',
    generatedAt: '2026-09-27T05:00:00Z',
  }

  function renderViews(initialProps: { threadId: string; viewKey?: string }) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const controller = {
      bodyRef: { current: null },
      events: [] as ThreadEventRecord[],
      working: false,
      sessionId: null,
      thread: undefined,
      modelInvocation: null,
      models: [],
    }
    return renderHook(
      ({ threadId, viewKey }: { threadId: string; viewKey?: string }) =>
        useBoundThreadPanelViews(threadId, controller, { viewKey }),
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

  it('resets mode, selection and inspector when the local view identity changes', () => {
    const { result, rerender } = renderViews({
      threadId: '',
      viewKey: 'branch:session-1:entry-2:branch-1',
    })
    openDebugPreview(result)
    expect(result.current.mode).toBe('debug')
    expect(result.current.debugSelection).toEqual({ type: 'preview', preview: PREVIEW })

    rerender({ threadId: '', viewKey: 'branch:session-1:entry-3:branch-2' })

    expect(result.current.mode).toBe('conversation')
    expect(result.current.selectedEventId).toBeNull()
    expect(result.current.debugSelection).toBeNull()
    expect(result.current.initialConversationScrollTop).toBeNull()
    expect(result.current.mainView.debug).toBeUndefined()
  })

  it('also resets when a draft pane is rebound to a new session draft', () => {
    // 新建 Session 草稿没有 Debug 能力，残留的 Debug 模式会连 composer 一起隐藏。
    const { result, rerender } = renderViews({
      threadId: '',
      viewKey: 'branch:session-1:entry-2:branch-1',
    })
    openDebugPreview(result)

    rerender({ threadId: '', viewKey: '' })

    expect(result.current.mode).toBe('conversation')
    expect(result.current.debugSelection).toBeNull()
  })

  it('resets the inspector when a bound thread identity changes', () => {
    // 已绑定 Thread 改用真实 threadId 作为身份（未传 viewKey），换 Thread 同样清零。
    const { result, rerender } = renderViews({ threadId: 'thread-a' })
    openDebugPreview(result)

    rerender({ threadId: 'thread-b' })

    expect(result.current.mode).toBe('conversation')
    expect(result.current.debugSelection).toBeNull()
  })

  it('keeps the debug view while the identity is unchanged', () => {
    const { result, rerender } = renderViews({
      threadId: '',
      viewKey: 'branch:session-1:entry-2:branch-1',
    })
    openDebugPreview(result)

    rerender({ threadId: '', viewKey: 'branch:session-1:entry-2:branch-1' })

    expect(result.current.mode).toBe('debug')
    expect(result.current.debugSelection).toEqual({ type: 'preview', preview: PREVIEW })
  })
})
