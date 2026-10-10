import { useEffect, useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import {
  branchDraftFromEntry,
  branchDraftFromEntryPath,
  sessionSelectionItem,
  threadSelectionItem,
  useRootThreadControl,
  type UseRootThreadControlOptions,
} from '@/features/ai/runtime/useRootThreadControl'
import { usePaneTarget } from '@/features/ai/runtime/usePaneTarget'
import { isBoundTarget, type PaneTarget } from '@/features/ai/runtime/agent-pane'
import { useThreadProjection } from '@/features/ai/runtime/useThreadProjection'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import { createTextPart, partsToText } from '@/features/ai/composer/composer-parts'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { interactionService } from '@/shared/api/interaction-service'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessModelRequestDebugDTO,
  HarnessSessionDTO,
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  ModelInvocationDTO,
  ProviderRequestPreviewDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import { rootYoloPolicy } from '@/test-support/thread-yolo-policy'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import {
  loadThreadDraft,
  saveThreadDraftParts,
} from '@/features/ai/runtime/thread-draft-store'
import { queryKeys } from '@/shared/lib/query-keys'
import { setLocale } from '@/shared/i18n'
import { createMockIDBFactory } from '@/features/canvas/__tests__/mock-idb'

const CHAT_ID = 'chat-1'
const THREAD_ID = '11111111-2222-4333-8444-555555555555'

/**
 * 根控制面与上传注册表的挂载探针：包装真实实现，只统计挂载/卸载次数，不改变行为。
 * 用于实测「子代理目标 / 身份未确认时根控制 Hook 与上传注册表根本没有创建」，
 * 以及「绑定新根时根控制面没有被卸载重挂」，而不是只看 DOM 上有没有被禁用的按钮。
 */
interface RootControlProbe {
  handlePreview: () => Promise<unknown> | void
  previewDisabled: boolean
  previewDisabledReason: string | null
}

const { controlMounts, uploadMounts, latestControlRef } = vi.hoisted(() => ({
  controlMounts: { mount: vi.fn(), unmount: vi.fn() },
  uploadMounts: { mount: vi.fn(), unmount: vi.fn() },
  latestControlRef: { current: null as (RootControlProbe | null) },
}))

interface MountProbe {
  mount: () => void
  unmount: () => void
}

const resetMountProbes = () => {
  controlMounts.mount.mockClear()
  controlMounts.unmount.mockClear()
  uploadMounts.mount.mockClear()
  uploadMounts.unmount.mockClear()
}

/** 包装 Hook 里记录这一层组件的挂载与卸载（命名成 Hook 以便被包装 Hook 调用）。 */
function useMountProbe(probe: MountProbe) {
  useEffect(() => {
    probe.mount()
    return () => {
      probe.unmount()
    }
  }, [probe])
}

vi.mock('@/features/ai/runtime/useRootThreadControl', async (importOriginal) => {
  const actual = await importOriginal<
    typeof import('@/features/ai/runtime/useRootThreadControl')
  >()
  return {
    ...actual,
    useRootThreadControl: (options: Parameters<typeof actual.useRootThreadControl>[0]) => {
      useMountProbe(controlMounts)
      const control = actual.useRootThreadControl(options)
      latestControlRef.current = control
      return control
    },
  }
})

vi.mock('@/features/ai/composer/use-attachment-uploads', async (importOriginal) => {
  const actual = await importOriginal<
    typeof import('@/features/ai/composer/use-attachment-uploads')
  >()
  return {
    ...actual,
    useAttachmentUploads: (options: Parameters<typeof actual.useAttachmentUploads>[0]) => {
      useMountProbe(uploadMounts)
      return actual.useAttachmentUploads(options)
    },
  }
})

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: vi.fn(() => vi.fn()) }
  return { fakeApplicationEvents: { useApplicationEvents: () => manager } }
})

/**
 * 附件上传默认走真实 storage 单例与真实 Web Worker 哈希；这里整体替换成
 * 内存实现，保留组件的 reserve → put → complete 调用路径，但禁止任何真实网络。
 */
const { fakeStorage } = vi.hoisted(() => {
  const reserveUpload = vi.fn(async () => ({
    id: '99999999-8888-4777-8666-555555555555',
    state: 'PENDING' as const,
    blobId: null,
    presignedPut: { method: 'PUT' as const, url: 'https://storage.test/put', headers: {} },
    expiresAt: null,
  }))
  const completeUpload = vi.fn(async (uploadId: string) => ({
    id: uploadId,
    state: 'READY' as const,
    blobId: `blob-${uploadId}`,
    presignedPut: null,
    expiresAt: null,
  }))
  const deleteUpload = vi.fn(async () => undefined)
  const uploadFile = vi.fn(async () => undefined)
  return {
    fakeStorage: {
      reserveUpload,
      completeUpload,
      deleteUpload,
      uploadFile,
      getBlobDownloadUrl: vi.fn(async () => ({ url: 'https://storage.test/blob', expiresAt: null })),
      getBlobPreviewUrl: vi.fn(async () => null),
    },
  }
})

vi.mock('@/shared/api/storage-service', () => ({
  storageService: fakeStorage,
  createStorageService: () => fakeStorage,
}))

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
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
    getEnvironmentUpdate: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
  },
}))
vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChatSessions: vi.fn(),
  },
}))
// 根交互卡片：根面板通过 GET /interactions?rootThreadId= 汇聚待决审批与问卷。
vi.mock('@/shared/api/interaction-service', () => ({
  interactionService: {
    listInteractions: vi.fn(),
    submitInteraction: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    acceptCommandBatch: vi.fn(),
    acceptThreadCommandBatch: vi.fn(),
    listSessionThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    getThreadSnapshot: vi.fn(),
    getModelRequestDebug: vi.fn(),
    compactThread: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
    renameSession: vi.fn(),
    renameThread: vi.fn(),
    previewProviderRequest: vi.fn(),
    previewBranchRequest: vi.fn(),
    getThreadTree: vi.fn(),
  },
}))

const agents: AgentDefinitionDTO[] = [{
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  environmentId: 'env-local-1',
  config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}]

const models = [{
  providerName: 'minimax',
  name: 'MiniMax',
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
}]

function thread(
  overrides: Partial<HarnessThreadDTO> & { yoloEnabled?: boolean } = {},
): HarnessThreadDTO {
  const { yoloEnabled = false, ...rest } = overrides
  return {
    /** Thread 名称（服务端权威必填非空）。 */
    name: 'thread-name',
    threadId: THREAD_ID,
    sessionId: 'session-1',
    headEntryId: 'head-1',
    parentThreadId: null,
    yoloPolicy: rootYoloPolicy(yoloEnabled),
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    processing: false,
    /** 持久执行控制：RUNNABLE / STOPPED，与本地展示 status 无关。 */
    executionControl: 'RUNNABLE',
    branchSettings: {
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      environmentName: null,
    },
    createTime: null,
    updateTime: null,
    ...rest,
  }
}

function snapshot(
  currentThread = thread(),
  overrides: Partial<HarnessThreadSnapshotDTO> = {},
): HarnessThreadSnapshotDTO {
  return {
    version: currentThread.version,
    thread: currentThread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    manualCompaction: { available: false, disabledReason: 'not available' },
    stopReceipts: [],
    ...overrides,
  }
}

function acceptedResponse(currentThread = thread()) {
  const rootEntry: HarnessSessionEntryDTO = {
    entryId: 'root-1',
    sessionId: currentThread.sessionId,
    parentEntryId: null,
    entryType: 'ROOT',
    payloadJson: '{}',
    createTime: null,
  }
  return {
    session: {
      sessionId: currentThread.sessionId,
      /** Session 名称（服务端权威必填非空）。 */
      name: currentThread.name,
      createdAt: null,
    },
    rootEntry,
    thread: currentThread,
    acceptedCommands: [],
    replayed: false,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  setLocale('zh-CN')
  vi.mocked(agentService.listAgents).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: agents.length,
    results: agents,
  })
  vi.mocked(agentService.listModels).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: models.length,
    results: models,
  })
  vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot())
  // Debug 结构化投影默认不可用：只有显式声明的用例才渲染 Debug 区（含预览入口）。
  vi.mocked(harnessService.getModelRequestDebug).mockReset()
  vi.mocked(harnessService.previewProviderRequest).mockReset()
  vi.mocked(harnessService.previewBranchRequest).mockReset()
  vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
  // 无活跃后代仍返回真实根；空数组不符合执行树读取契约。
  vi.mocked(harnessService.getThreadTree).mockImplementation(async (threadId) => [
    treeNode(threadId, null, 'thread-name', false),
  ])
  vi.mocked(interactionService.listInteractions).mockResolvedValue({
    items: [],
    nextCursor: null,
  })
  vi.mocked(harnessService.acceptCommandBatch).mockResolvedValue(acceptedResponse())
  vi.mocked(harnessService.acceptThreadCommandBatch).mockResolvedValue(acceptedResponse())
  vi.mocked(harnessService.setThreadYolo).mockImplementation((threadId, data) =>
    Promise.resolve(threadFixture(threadId, { yoloEnabled: data.yoloEnabled, version: '1' })),
  )
  vi.mocked(harnessService.renameSession).mockImplementation(async (sessionId, data) =>
    thread({ sessionId, name: data.name }),
  )
  vi.mocked(harnessService.renameThread).mockImplementation(async (threadId, data) =>
    thread({ threadId, name: data.name }),
  )
})

afterEach(() => {
  // 内存 Worker 只服务附件用例，不污染同文件其它用例
  vi.unstubAllGlobals()
})

/** 活跃执行树节点 DTO：根与后代共用同一形状。 */
function treeNode(
  threadId: string,
  parentThreadId: string | null,
  name: string,
  processing: boolean,
) {
  return {
    threadId,
    parentThreadId,
    name,
    agentName: parentThreadId == null ? 'assistant' : 'coder',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    status: processing ? 'MODEL_STREAM' : 'IDLE',
    processing,
    turnCount: 1,
    toolCallCount: 0,
    outcome: null,
    updateTime: 1774958400,
  }
}

/** 根交互汇聚里的一个待决审批（来源 Thread 可指定）。 */
function approvalInteraction(threadId: string) {
  return {
    type: 'APPROVAL',
    interactionId: `inv-${threadId}`,
    status: 'WAITING_APPROVAL',
    threadId,
    rootThreadId: THREAD_ID,
    sessionId: 'session-1',
    owner: {
      type: 'CHAT',
      chatId: CHAT_ID,
      chatTitle: null,
      issueId: null,
      issueTitle: null,
      agentName: null,
      rootThreadName: null,
    },
    toolCallId: 'call-bash-1',
    toolName: 'bash',
    argumentsJson: '{"command":"ls"}',
    approvalJson: JSON.stringify({ reason: '需要确认', decision: null }),
    environmentId: null,
    environmentName: null,
    waitingCount: null,
    createTime: '2026-07-28T10:00:01Z',
  }
}

describe('AgentPane orchestration', () => {
  const historyRoot: HarnessSessionEntryDTO = {
    entryId: 'draft-root', sessionId: 'session-1', parentEntryId: null, entryType: 'ROOT',
    payloadJson: JSON.stringify({ settings: {
      agentName: 'assistant', model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' }, environmentName: null,
    } }), createTime: null,
  }
  const historyMessage: HarnessSessionEntryDTO = {
    ...historyRoot, entryId: 'draft-message', parentEntryId: 'draft-root', entryType: 'MESSAGE',
    payloadJson: JSON.stringify({ message: { role: 'USER', contents: [{ type: 'text', text: 'Selected ancestor text' }] } }),
  }
  const historyEnd: HarnessSessionEntryDTO = {
    ...historyRoot, entryId: 'draft-end', parentEntryId: 'draft-message', entryType: 'TURN_END', payloadJson: '{"outcome":"COMPLETED"}',
  }
  const historyEntries = [historyRoot, historyMessage, historyEnd, {
    ...historyMessage, entryId: 'future', parentEntryId: 'draft-end',
    payloadJson: JSON.stringify({ message: { role: 'USER', contents: [{ type: 'text', text: 'Future hidden text' }] } }),
  }, {
    ...historyMessage, entryId: 'sibling', parentEntryId: 'draft-root',
    payloadJson: JSON.stringify({ message: { role: 'USER', contents: [{ type: 'text', text: 'Sibling hidden text' }] } }),
  }]
  function seedHistoryDraft(startEntryId = 'draft-end') {
    localStorage.setItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`, JSON.stringify({
      kind: 'NEW_THREAD_DRAFT', sessionId: 'session-1', startEntryId, threadName: 'draft-name',
    }))
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue(historyEntries)
  }

  it('renders the complete selected prefix, including Debug events, without any command writes', async () => {
    const user = userEvent.setup()
    seedHistoryDraft()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    expect(await screen.findByText('Selected ancestor text')).toBeInTheDocument()
    expect(screen.queryByText('Future hidden text')).not.toBeInTheDocument()
    expect(screen.queryByText('Sibling hidden text')).not.toBeInTheDocument()
    const composer = screen.getByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/debug{Enter}')
    const events = await screen.findByRole('listbox', { name: '事件' })
    expect(events.querySelectorAll('[role="option"]')).toHaveLength(3)
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
    expect(harnessService.acceptThreadCommandBatch).not.toHaveBeenCalled()
  })

  it('propagates history load errors and retry; ROOT is a genuine draft empty state', async () => {
    const user = userEvent.setup()
    seedHistoryDraft('draft-root')
    vi.mocked(harnessService.listSessionEntries).mockRejectedValueOnce(new Error('offline'))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await screen.findByRole('alert')
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('已打开 thread 草稿，发送消息开始此 thread。')).toBeInTheDocument()
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it('rejects an invalid prefix rather than allowing first send or preview', async () => {
    seedHistoryDraft('missing')
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    expect(await screen.findByRole('alert')).toHaveTextContent('所选历史不完整，请重新加载后再发送。')
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    expect(screen.queryByText('已打开 thread 草稿，发送消息开始此 thread。')).not.toBeInTheDocument()
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it.each(['error', 'missing'])('keeps unresolved Session rename failures visible without losing the draft (%s)', async (failure) => {
    const user = userEvent.setup()
    seedHistoryDraft()
    if (failure === 'error') {
      vi.mocked(chatService.listChatSessions).mockRejectedValueOnce(new Error('Session summaries offline'))
    } else {
      vi.mocked(chatService.listChatSessions).mockResolvedValueOnce([])
    }
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await screen.findByText('Selected ancestor text')
    const composer = screen.getByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/rename-session{Enter}')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(failure === 'error' ? 'Session summaries offline' : 'Session')
    expect(screen.getByRole('textbox', { name: '名称' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(screen.getByText('Selected ancestor text')).toBeInTheDocument()
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it('selects an existing Thread through the picker and keeps inline rename cancellation local', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1', name: 'Session One', createdAt: null, lastActivityAt: null,
      firstMessagePreview: null, threadCount: 1,
    }])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([{
      threadId: THREAD_ID, parentThreadId: null, name: 'thread-name',
      createdAt: null, updatedAt: null, status: 'IDLE', processing: false,
      model: thread().branchSettings.model, headMessagePreview: null,
    }])
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /Session One/ }))
    const row = await screen.findByRole('option', { name: /thread-name/ })
    await user.click(row.parentElement!.querySelector('.thread-selection-rename') as HTMLElement)
    expect(await screen.findByRole('textbox', { name: '名称' })).toHaveValue('thread-name')
    await user.click(screen.getByRole('button', { name: '取消' }))
    await user.click(await screen.findByRole('option', { name: /thread-name/ }))
    await waitFor(() => expect(localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`)).toContain('BOUND_THREAD'))
    expect(harnessService.renameThread).not.toHaveBeenCalled()
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it('resolves the parent Session name from a bound root without changing its draft', async () => {
    const user = userEvent.setup()
    localStorage.setItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`, JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }))
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1', name: 'Parent Session', createdAt: null, lastActivityAt: null,
      firstMessagePreview: null, threadCount: 1,
    }])
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const editor = await screen.findByLabelText('给 AI 发送消息')
    await user.click(editor)
    await user.keyboard('/rename-session{Enter}')
    const name = await screen.findByRole('textbox', { name: '名称' })
    await waitFor(() => expect(name).toHaveValue('Parent Session'))
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(harnessService.renameSession).not.toHaveBeenCalled()
  })

  it.each(['missing-host', 'unmounted'])('fails closed or discards late draft rename validation (%s)', async (scenario) => {
    const user = userEvent.setup()
    seedHistoryDraft()
    let resolve!: (error: string | null) => void
    const view = renderPane({ type: 'CHAT', chatId: CHAT_ID }, [], scenario === 'missing-host'
      ? null : () => new Promise((done) => { resolve = done }))
    await screen.findByText('Selected ancestor text')
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.click(editor)
    await user.keyboard('/rename-thread{Enter}')
    const name = await screen.findByRole('textbox', { name: '名称' })
    await user.clear(name)
    await user.type(name, 'late-name')
    await user.click(screen.getByRole('button', { name: '保存' }))
    if (scenario === 'missing-host') {
      expect(await screen.findByRole('alert')).toHaveTextContent('无法检查 thread 名称')
      expect(name).toHaveValue('late-name')
    } else {
      view.unmount()
      await act(async () => resolve(null))
    }
    expect(localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`)).toContain('"threadName":"draft-name"')
    expect(harnessService.renameThread).not.toHaveBeenCalled()
  })

  it('restores input and settings after name competition, locally renames, and seeds the same prefix while binding', async () => {
    const user = userEvent.setup()
    seedHistoryDraft()
    vi.mocked(harnessService.acceptCommandBatch)
      .mockRejectedValueOnce(new ApiError('name conflict', 409, 'THREAD_NAME_CONFLICT'))
      .mockResolvedValueOnce(acceptedResponse())
    // Keep the newly bound snapshot in flight: the accepted local prefix must not flash empty.
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(() => new Promise(() => undefined))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await screen.findByText('Selected ancestor text')
    const composer = screen.getByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/yolo{Enter}')
    await user.type(composer, 'Keep my message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    const name = await screen.findByRole('textbox', { name: '名称' })
    expect(name).toHaveValue('draft-name')
    expect(composer).toHaveTextContent('Keep my message')
    expect(screen.getByText('Selected ancestor text')).toBeInTheDocument()
    await user.clear(name)
    await user.type(name, ' recovered\u0085name ')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(harnessService.renameThread).not.toHaveBeenCalled()
    expect(localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`)).toContain('"threadName":"recovered name"')
    expect(composer).toHaveTextContent('Keep my message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    const requests = vi.mocked(harnessService.acceptCommandBatch).mock.calls.map(([request]) => request)
    expect(requests[1]!.target).toMatchObject({ type: 'NEW_THREAD', startEntryId: 'draft-end', threadName: 'recovered name' })
    const commandContents = (request: typeof requests[number]) => request.commands.map(({ idempotencyKey: _key, ...command }) => command)
    expect(commandContents(requests[1]!)).toEqual(commandContents(requests[0]!))
    expect(requests[1]!.target).toMatchObject({ yoloEnabled: true })
    await waitFor(() => expect(localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`)).toContain('BOUND_THREAD'))
    expect(screen.getByText('Selected ancestor text')).toBeInTheDocument()
  })

  it('sends NEW_SESSION as one atomic command-batches request', async () => {
    const user = userEvent.setup()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'first message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.target.type).toBe('NEW_SESSION')
    expect(request.target).not.toHaveProperty('kind')
    expect(request.commands).toHaveLength(1)
    expect(request.commands[0]?.type).toBe('USER_MESSAGE')
  })

  it('sends a NEW_THREAD target with zero settings writes when the draft is unchanged', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([{
      entryId: 'entry-1', sessionId: 'session-1', parentEntryId: null,
      entryType: 'ROOT', payloadJson: '{}', createTime: null,
    }])
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({
        kind: 'NEW_THREAD_DRAFT',
        sessionId: 'session-1',
        startEntryId: 'entry-1',
        threadName: 'branch-1',
      }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'branch message')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.target.type).toBe('NEW_THREAD')
    expect(request.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
  })

  it('retries an unknown outcome with the exact request and clears the old action error', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch)
      .mockRejectedValueOnce(new Error('connection lost'))
      .mockResolvedValueOnce(acceptedResponse())
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'retry me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    const retry = await screen.findByRole('button', { name: '重试' })
    const firstRequest = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]?.[0]

    await user.click(retry)
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    expect(vi.mocked(harnessService.acceptCommandBatch).mock.calls[1]?.[0]).toBe(firstRequest)
    expect(screen.queryByText('connection lost')).not.toBeInTheDocument()
  })

  it('restores an unknown pending acceptance after remount with the same frozen request', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(new Error('timeout'))
    const firstRender = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'persist me')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await screen.findByRole('button', { name: '重试' })
    const firstRequest = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]?.[0]
    firstRender.unmount()

    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const retry = await screen.findByRole('button', { name: '重试' })
    await user.click(retry)
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    expect(vi.mocked(harnessService.acceptCommandBatch).mock.calls[1]?.[0]).toEqual(firstRequest)
  })

  it('abandons an unknown outcome by restoring the frozen composer parts', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(new Error('timeout'))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'frozen draft')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await screen.findByRole('button', { name: '重试' })

    await user.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => expect(composer).toHaveTextContent('frozen draft'))
  })

  it('restores the frozen request on a definite 409 and presents its reason', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('entry changed', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'restore after conflict')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('STALE_COMMAND_CURSOR')
    await waitFor(() => expect(composer).toHaveTextContent('restore after conflict'))
  })

  it('fences a late success after abandon without changing the target', async () => {
    const user = userEvent.setup()
    let resolve: ((value: ReturnType<typeof acceptedResponse>) => void) | null = null
    vi.mocked(harnessService.acceptCommandBatch).mockImplementationOnce(
      () => new Promise((complete) => {
        resolve = complete
      }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'late response')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await user.click(await screen.findByRole('button', { name: '取消' }))
    resolve?.(acceptedResponse())

    await waitFor(() => expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('NEW_SESSION_DRAFT'))
    expect(vi.mocked(harnessService.acceptCommandBatch)).toHaveBeenCalledTimes(1)
  })

  it('ignores a late rejection after abandon without restoring a stale error', async () => {
    const user = userEvent.setup()
    let reject: ((error: Error) => void) | null = null
    vi.mocked(harnessService.acceptCommandBatch).mockImplementationOnce(
      () => new Promise((_, fail) => {
        reject = fail
      }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'late rejection')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await user.click(await screen.findByRole('button', { name: '取消' }))
    reject?.(new Error('stale rejection'))
    await waitFor(() => expect(screen.queryByText('stale rejection')).not.toBeInTheDocument())
  })

  it('uses the action error path for a definite non-conflict acceptance failure', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('locked', 400, 'BAD_REQUEST'),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'locked request')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('locked')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('projects bound 409s through the single structured conflict presenter', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.acceptThreadCommandBatch).mockRejectedValueOnce(
      new ApiError('state changed', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'stale')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('STALE_COMMAND_CURSOR')
    expect(dialog).toHaveTextContent('state changed')
    expect(screen.getAllByRole('alertdialog')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: '刷新' }))
    await user.click(screen.getByRole('button', { name: '取消' }))
  })

  it('projects a YOLO 409 through the same presenter instead of actionError', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.setThreadYolo).mockRejectedValueOnce(
      new ApiError('yolo state changed', 409, 'CONFLICT', { reason: 'STALE_VERSION' }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/yolo{Enter}')

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('STALE_VERSION')
    expect(screen.queryByText('yolo state changed')).toBeInTheDocument()
    expect(screen.getAllByRole('alertdialog')).toHaveLength(1)
  })

  it('keeps a definite non-conflict YOLO failure in the bound action error channel', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.setThreadYolo).mockRejectedValueOnce(
      new ApiError('yolo locked', 400, 'BAD_REQUEST'),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/yolo{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent('yolo locked')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('disables and enables compact from the advisory snapshot sidecar', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.compactThread).mockResolvedValueOnce({
      thread: thread({ version: '1' }),
      turnStartEntryId: 'turn-start-1',
      childThreadId: 'child-1',
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    render(
      <QueryClientProvider client={client}>
        <AgentPane
          owner={{ type: 'CHAT', chatId: CHAT_ID }}
          paneId="pane-1"
          agents={agents}
          focused
        />
      </QueryClientProvider>,
    )
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/')
    expect(screen.getByRole('option', { name: /^compact/ })).toHaveAttribute('aria-disabled', 'true')
    await user.keyboard('{Escape}')

    client.setQueryData(
      queryKeys.threads.snapshot(THREAD_ID),
      snapshot(thread(), { manualCompaction: { available: true, disabledReason: null } }),
    )
    await user.click(composer)
    await user.keyboard('/')
    await waitFor(() =>
      expect(screen.getByRole('option', { name: /^compact/ })).toHaveAttribute('aria-disabled', 'false'),
    )
    await user.click(screen.getByRole('option', { name: /^compact/ }))
    await waitFor(() =>
      expect(harnessService.compactThread).toHaveBeenCalledWith(
        THREAD_ID,
        { expectedVersion: '0' },
      ),
    )
  })

  it('toggles the debug view without duplicating the target or presenter', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/debug{Enter}')
    expect(await screen.findByRole('listbox', { name: '事件' })).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: '给 AI 发送消息' })).not.toBeInTheDocument()
    expect(composer.isConnected).toBe(true)
    await user.click(screen.getByRole('button', { name: '关闭 Debug', exact: true }))
    expect(screen.queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument()
  })

  it('dismisses a non-conflict action error through the shared panel callback', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(new Error('network lost'))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'network error')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: '关闭错误' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('opens and closes a selected debug event detail without another presenter', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const rootEntry: HarnessSessionEntryDTO = {
      entryId: 'root-1',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    }
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread(), { entries: [rootEntry] }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/debug{Enter}')
    const event = await screen.findByRole('option')
    await user.click(event)
    expect(await screen.findByRole('region', { name: '事件详情' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '关闭事件详情' }))
    expect(screen.queryByRole('region', { name: '事件详情' })).not.toBeInTheDocument()
  })

  it('renders the Agent, Environment, and Shortcuts interactions from the shared command menu', async () => {
    const user = userEvent.setup()
    renderPane({ type: 'CHAT', chatId: CHAT_ID }, [{
      id: 'env-local-1',
      name: 'local',
      userName: null,
      homeDirectory: null,
      ready: true,
      status: 'READY',
      lastSeen: null,
      capabilities: [],
      version: '1',
      createTime: null,
      updateTime: null,
    }])
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    expect(await screen.findByText('assistant')).toBeInTheDocument()
    await user.keyboard('{Escape}')

    await user.click(composer)
    await user.keyboard('/shortcuts{Enter}')
    expect(await screen.findByRole('region', { name: '键盘快捷键' })).toBeInTheDocument()
  })

  it('applies unbound YOLO and agent selections locally before first send', async () => {
    const user = userEvent.setup()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/yolo{Enter}')
    await user.click(composer)
    await user.keyboard('/agent{Enter}')
    await user.click(await screen.findByRole('option', { name: /assistant/ }))
    expect(screen.getByRole('button', { name: '权限模式' })).toHaveTextContent('YOLO')
  })

  it('navigates Session -> Thread through the owner-scoped queries', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'Session Name',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'first',
      threadCount: 1,
    }])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([{
      threadId: THREAD_ID,
      name: 'Thread Name',
      parentThreadId: null,
      createdAt: null,
      updatedAt: null,
      status: 'IDLE',
      processing: false,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: 'thread preview',
    }])
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /first/ }))
    await user.click(screen.getByRole('button', { name: '关闭' }))
    await user.click(await screen.findByRole('option', { name: /first/ }))
    await user.click(await screen.findByRole('option', { name: /thread preview/ }))
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(THREAD_ID))
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('BOUND_THREAD')
  })

  it('views any Entry in the History Tree and forks only from a legal boundary', async () => {
    // 历史树的契约：每个真实 Entry 一行；点击任意行只改本地选中（不发请求、不写草稿），
    // 只有 ROOT / 已关闭 TURN_END 行允许新建分支，其余行给出禁用原因。
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({
        kind: 'NEW_THREAD_DRAFT',
        sessionId: 'session-1',
        startEntryId: 'entry-1',
        threadName: 'branch-1',
      }),
    )
    const targetKey = `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([
      {
        entryId: 'entry-1',
        sessionId: 'session-1',
        parentEntryId: null,
        entryType: 'ROOT',
        payloadJson: '{}',
        createTime: null,
      },
      {
        entryId: 'entry-2',
        sessionId: 'session-1',
        parentEntryId: 'entry-1',
        entryType: 'TURN_END',
        payloadJson: JSON.stringify({ status: 'TURN_END', turnId: 'turn-2' }),
        createTime: null,
      },
      {
        entryId: 'entry-3',
        sessionId: 'session-1',
        parentEntryId: 'entry-2',
        entryType: 'AGENT_MESSAGE',
        payloadJson: JSON.stringify({ message: { role: 'AGENT', contents: [] } }),
        createTime: null,
      },
    ])
    const before = localStorage.getItem(targetKey)
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/history{Enter}')

    const list = await screen.findByRole('list')
    const rows = await waitFor(() => {
      const found = document.querySelectorAll('.history-tree-entry')
      expect(found).toHaveLength(3)
      return found
    })
    // 选中头部（TURN_END）可直接分叉；点击 AGENT_MESSAGE 行只改本地选中。
    await user.click(rows[2] as HTMLElement)
    expect(rows[2]?.getAttribute('data-can-fork')).toBe('false')
    // 面板文案由父切片注入 i18n 目录；这里按结构断言动作按钮与禁用原因。
    const forkButton = document.querySelector<HTMLButtonElement>('.history-tree-actions .btn-primary')!
    expect(forkButton).toBeDisabled()
    expect(forkButton).toHaveAttribute('title')
    expect(screen.getByRole('note')).toBeInTheDocument()
    expect(localStorage.getItem(targetKey)).toBe(before)
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()

    await user.click(rows[1] as HTMLElement)
    expect(rows[1]?.getAttribute('data-can-fork')).toBe('true')
    expect(forkButton).toBeEnabled()
    // 无 workspace 回调（本用例直接挂载面板）时不产生任何副作用。
    await user.click(forkButton)
    expect(localStorage.getItem(targetKey)).toBe(before)
    expect(list).toBeInTheDocument()
  })

  it('opens the on-demand Entry Tree for a bound Thread target', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/history{Enter}')
    await waitFor(() => expect(document.querySelector('.history-tree-panel')).not.toBeNull())
    expect(harnessService.listSessionEntries).toHaveBeenCalledWith('session-1')
  })

  it('blocks target navigation while an exact Stop replay is pending', async () => {
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    localStorage.setItem(
      `kkstudio.ai.pending-stop.v1:${THREAD_ID}`,
      JSON.stringify({
        stopRequestId: 'stop-1',
        expectedVersion: '0',
        requestHeadEntryId: 'head-1',
        basisVersion: '0',
      }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.controller.stopReplayPending).toBe(true))
    expect(hook.result.current.pending).toBe(true)
    expect(hook.result.current.composer.pending).toBe(true)

    act(() => hook.result.current.composer.onCommand(testCommand('new')))
    expect(hook.result.current.target).toEqual({
      kind: 'BOUND_THREAD',
      threadId: THREAD_ID,
    })
    expect(hook.result.current.error).toContain('等待完成或重试原操作')

    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBeNull()
  })

  it('keeps a background realtime version from changing the persisted target', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ status: 'MODEL_STREAMING', processing: true })),
    )
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/new{Enter}')
    await waitFor(() => expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('NEW_SESSION_DRAFT'))
    const calls = fakeApplicationEvents.useApplicationEvents().subscribe.mock.calls
    const handlers = calls.at(-1)?.[1]
    handlers?.onSubscribed?.()
    handlers?.onEvent?.('unrelated', {})
    handlers?.onResync?.()
    handlers?.onError?.()
    view.client.setQueryData(
      queryKeys.threads.snapshot(THREAD_ID),
      snapshot(thread({ status: 'IDLE', processing: false })),
    )
    handlers?.onEvent?.('version', {})
    await waitFor(() => expect(
      fakeApplicationEvents.useApplicationEvents().subscribe.mock.results.at(-1)?.value,
    ).toHaveBeenCalled())
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('NEW_SESSION_DRAFT')
  })

  it('cleans and reuses the single background subscription across thread switches', async () => {
    const user = userEvent.setup()
    const threadA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    const threadB = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
    const summary = (threadId: string, name: string) => ({
      threadId,
      parentThreadId: null,
      name,
      createdAt: null,
      updatedAt: null,
      status: 'MODEL_STREAMING' as const,
      processing: true,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) =>
      snapshot(thread({ threadId, status: 'MODEL_STREAMING', processing: true })),
    )
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: null,
      threadCount: 1,
    }])
    vi.mocked(harnessService.listSessionThreads)
      .mockResolvedValueOnce([summary(threadB, 'thread B')])
      .mockResolvedValueOnce([summary(threadA, 'thread A')])
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: threadA }),
    )
    const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /session/ }))
    await user.click(await screen.findByRole('option', { name: /thread B/ }))
    await waitFor(() => expect(fakeApplicationEvents.useApplicationEvents().subscribe).toHaveBeenCalled())
    const subscribe = fakeApplicationEvents.useApplicationEvents().subscribe
    const firstBackgroundUnsubscribe = subscribe.mock.results.at(-1)?.value as ReturnType<typeof vi.fn>
    const replacementClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    view.rerender(
      <QueryClientProvider client={replacementClient}>
        <MemoryRouter>
          <AgentPane owner={{ type: 'CHAT', chatId: CHAT_ID }} paneId="pane-1" agents={agents} focused />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    // 新 QueryClient 下 Thread 身份需要重新加载：根控制区在身份就绪前不出现，
    // 因此这里重新获取 composer 而不是复用重绑前的节点。
    const composerAfterRebind = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composerAfterRebind)
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /session/ }))
    await user.click(await screen.findByRole('option', { name: /thread A/ }))
    await waitFor(() => expect(firstBackgroundUnsubscribe).toHaveBeenCalled())
    expect(subscribe.mock.calls.length).toBeGreaterThan(3)
  })

  it('covers unbound command guards and draft controls through the real pane controller', async () => {
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())

    act(() => hook.result.current.composer.onCommand(testCommand('history')))
    expect(hook.result.current.error).toBeTruthy()
    act(() => hook.result.current.dismissActionError())

    act(() => hook.result.current.composer.onCommand(testCommand('debug')))
    expect(hook.result.current.interaction).toBeNull()
    act(() => hook.result.current.composer.onCommand(testCommand('models')))
    act(() => hook.result.current.composer.onCommand(testCommand('upload')))
    expect(hook.result.current.error).toBeNull()

    act(() => hook.result.current.composer.onCommand(testCommand('disabled', {
      disabled: true,
      disabledReason: 'disabled for test',
    })))
    expect(hook.result.current.error).toBe('disabled for test')
    act(() => hook.result.current.dismissActionError())
    act(() => hook.result.current.composer.onCommand(testCommand('disabled', {
      disabled: true,
    })))
    act(() => hook.result.current.retryAcceptance())

    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBe('thread-sessions')
    act(() => hook.result.current.closeInteraction())
    act(() => hook.result.current.selectSession({
      sessionId: 'empty-session',
      name: 'Empty Session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: '',
      threadCount: 0,
    }))
    expect(hook.result.current.interaction).toBe('history')
    act(() => hook.result.current.selectSession({
      sessionId: 'threaded-session',
      name: 'Threaded Session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: '',
      threadCount: 1,
    }))
    expect(hook.result.current.interaction).toBe('thread-threads')

    act(() => hook.result.current.selectAgent('missing-agent'))
    expect(hook.result.current.error).toBeTruthy()
    act(() => hook.result.current.selectAgent('assistant'))
    expect(hook.result.current.error).toBeNull()
    act(() => hook.result.current.composer.settings?.onModelChange({
      providerName: 'minimax',
      modelName: 'MiniMax',
      variant: 'default',
    }))
    act(() => hook.result.current.composer.settings?.onYoloChange(true))
    expect(hook.result.current.activeDraft?.yoloEnabled).toBe(true)

    // 分叉不再改写本 pane 的目标：只向上请求（本用例无 workspace 回调，故无副作用）。
    const targetBeforeFork = hook.result.current.target
    act(() => hook.result.current.requestBranchFromEntry({
      entryId: 'entry-settings',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: JSON.stringify({
        settings: {
          agentName: 'assistant',
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        },
      }),
      createTime: null,
    }))
    expect(hook.result.current.target).toEqual(targetBeforeFork)
    act(() => hook.result.current.requestBranchFromEntry({
      entryId: 'entry-missing-settings',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: JSON.stringify({ settings: {} }),
      createTime: null,
    }))
    await hook.result.current.refreshPaneProjection()
  })

  it('keeps pending acceptance fenced while commands and a direct composer callback race', async () => {
    let resolve: ((value: ReturnType<typeof acceptedResponse>) => void) | null = null
    vi.mocked(harnessService.acceptCommandBatch).mockImplementationOnce(
      () => new Promise((complete) => {
        resolve = complete
      }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onSubmit([createTextPart('first')]))
    await waitFor(() => expect(hook.result.current.pendingAcceptance).not.toBeNull())

    act(() => hook.result.current.composer.onCommand(testCommand('new')))
    expect(hook.result.current.error).toBeTruthy()
    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    act(() => hook.result.current.composer.onCommand(testCommand('history')))
    act(() => hook.result.current.composer.onSubmit([createTextPart('second')]))
    act(() => hook.result.current.composer.onPartsChange([createTextPart('typed during request')]))
    resolve?.(acceptedResponse())
    await waitFor(() => expect(hook.result.current.pendingAcceptance).toBeNull())
    expect(hook.result.current.target.kind).toBe('BOUND_THREAD')
    act(() => hook.result.current.abandonPendingAcceptance())
  })

  it('takes the unbound submit early returns and yolo null-draft path', async () => {
    const hook = renderController({ agents: [] })
    await waitFor(() => expect(hook.result.current.activeDraft).toBeNull())
    act(() => hook.result.current.composer.onSubmit([createTextPart('/unknown-command')]))
    act(() => hook.result.current.composer.onSubmit([]))
    act(() => hook.result.current.composer.onCommand(testCommand('yolo')))
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it('uses stored composer parts when the submit callback omits an explicit payload', async () => {
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onPartsChange([createTextPart('stored parts')]))
    act(() => hook.result.current.composer.onSubmit())
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    expect(harnessService.acceptCommandBatch.mock.calls[0]?.[0].commands[0]?.type)
      .toBe('USER_MESSAGE')
  })

  it('restores the browser-local draft rather than the resolved submit payload', async () => {
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce(
      new ApiError('rejected', 400, 'BAD_REQUEST'),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())

    act(() =>
      hook.result.current.composer.onSubmit(
        [createTextPart('resolved payload')],
        [createTextPart('browser-local draft')],
      ),
    )

    await waitFor(() =>
      expect(hook.result.current.composer.parts).toEqual([
        expect.objectContaining({ type: 'text', text: 'browser-local draft' }),
      ]),
    )
  })

  it('keeps Chat thread navigation available', async () => {
    const hook = renderController({ owner: { type: 'CHAT', chatId: CHAT_ID } })
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBe('thread-sessions')
  })

  it('shows required names as primary selection titles and falls back previews only', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockRejectedValueOnce({})
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'unknown error')
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('请求失败')
    expect(sessionSelectionItem({
      sessionId: 'session-fallback',
      name: 'Named Session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'preview text',
      threadCount: 0,
    })).toMatchObject({
      id: 'session-fallback',
      title: 'Named Session',
      subtitle: expect.stringContaining('0 Threads'),
    })
    expect(threadSelectionItem({
      threadId: THREAD_ID,
      parentThreadId: null,
      name: 'Named Thread',
      createdAt: null,
      updatedAt: null,
      status: 'IDLE',
      processing: false,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    })).toMatchObject({
      id: THREAD_ID,
      title: 'Named Thread',
      subtitle: expect.stringContaining('IDLE'),
    })
    // 名称为主展示，绝不回退为 id 或 preview。
    expect(sessionSelectionItem({
      sessionId: 'session-fallback',
      name: 'Session With Preview',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'some other preview',
      threadCount: 0,
    }).title).toBe('Session With Preview')
  })

  it('renders Session/Thread pickers with the existing ai.chat translations', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatSessions).mockResolvedValue([])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([])
    const emptyPane = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const emptyComposer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(emptyComposer)
    await user.keyboard('/thread{Enter}')
    // 复用 ai.chat.* 既有 key，而不是未注册的 ai.runtime.* 缺失消息。
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()
    expect(screen.getByText('暂无 Session')).toBeInTheDocument()
    emptyPane.unmount()

    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
        sessionId: 'session-1',
        name: 'Session 1',
        createdAt: null,
        lastActivityAt: null,
        firstMessagePreview: 'session-1',
        threadCount: 1,
      }])
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()
    expect(document.querySelector('.thread-composer')).toHaveAttribute('hidden')
    await user.click(await screen.findByRole('option', { name: /session-1/ }))
    expect(await screen.findByRole('region', { name: '选择 Thread' })).toBeInTheDocument()
    expect(screen.getByText('暂无 Thread')).toBeInTheDocument()
    expect(screen.queryByText(/⟦missing:/)).not.toBeInTheDocument()
  })

  it('renames the bound Thread from the palette since identity moved to the workspace topbar', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.renameThread).mockImplementation(async (threadId, data) =>
      thread({ threadId, name: data.name, version: '1' }),
    )
    // 重命名成功后 snapshot 被失效并重新拉取，返回带新名称的 Thread。
    let snapshotCalls = 0
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async () => {
      snapshotCalls += 1
      return snapshot(snapshotCalls > 1
        ? thread({ name: 'renamed thread', version: '1' })
        : thread())
    })
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    // 面板不再自渲染执行根身份与铅笔入口；重命名保留在 /rename-thread 命令中。
    expect(document.querySelector('.agent-pane-thread-heading')).toBeNull()
    await user.click(composer)
    await user.keyboard('/rename-thread{Enter}')
    await screen.findByRole('region', { name: '重命名 thread' })
    const input = screen.getByRole('textbox', { name: '名称' })
    expect(input).toHaveValue('thread-name')
    await user.clear(input)
    await user.type(input, 'renamed thread')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() =>
      expect(harnessService.renameThread).toHaveBeenCalledWith(THREAD_ID, { name: 'renamed thread' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 thread' })).not.toBeInTheDocument())
    expect(composer).toBeInTheDocument()
    expect(harnessService.getThreadTree).toHaveBeenCalledWith(THREAD_ID)
  })

  it('auto-mounts the active subagent tree for a bound root without a manual toggle', async () => {
    // 活跃树是根面板的自动 widget：无需 toggle，挂载即查询；无活跃后代时不留空壳。
    const workerId = '00000000-0000-4000-8000-0000000000aa'
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      {
        threadId: THREAD_ID,
        parentThreadId: null,
        name: 'thread-name',
        agentName: 'assistant',
        model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        status: 'IDLE',
        processing: false,
        turnCount: 1,
        toolCallCount: 0,
        outcome: null,
        updateTime: 1774958400,
      },
      {
        threadId: workerId,
        parentThreadId: THREAD_ID,
        name: 'worker',
        agentName: 'coder',
        model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        status: 'MODEL_STREAM',
        processing: true,
        turnCount: 1,
        toolCallCount: 0,
        outcome: null,
        updateTime: 1774958400,
      },
    ])
    const bound = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    expect(screen.queryByRole('button', { name: 'Agent 关系' })).not.toBeInTheDocument()
    await waitFor(() => expect(harnessService.getThreadTree).toHaveBeenCalledWith(THREAD_ID))
    expect(await screen.findByRole('region', { name: '活跃子代理' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /worker/ })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '给 AI 发送消息' })).toBeInTheDocument()
    bound.unmount()

    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      {
        threadId: THREAD_ID,
        parentThreadId: null,
        name: 'thread-name',
        agentName: 'assistant',
        model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        status: 'IDLE',
        processing: false,
        turnCount: 1,
        toolCallCount: 0,
        outcome: null,
        updateTime: 1774958400,
      },
    ])
    const idle = renderPane({ type: 'CHAT', chatId: 'chat-idle' })
    await waitFor(() => expect(harnessService.getThreadTree).toHaveBeenCalledWith(THREAD_ID))
    expect(screen.queryByRole('region', { name: '活跃子代理' })).not.toBeInTheDocument()
    idle.unmount()

    const draft = renderPane({ type: 'CHAT', chatId: 'chat-draft' })
    await screen.findByLabelText('给 AI 发送消息')
    expect(screen.queryByRole('region', { name: '活跃子代理' })).not.toBeInTheDocument()
    draft.unmount()
  })

  it('isolates the active tree when the bound thread changes', async () => {
    // 活跃树跟随当前绑定；切换 Thread 时不得继续展示上一棵树。
    const user = userEvent.setup()
    const threadB = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
    const childA = 'aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa'
    const childB = 'bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb'
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.getThreadTree).mockImplementation(async (threadId) => [{
      threadId,
      parentThreadId: null,
      name: threadId === THREAD_ID ? 'tree A' : 'tree B',
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      status: 'IDLE',
      processing: false,
      turnCount: 1,
      toolCallCount: 0,
      outcome: null,
      updateTime: 1774958400,
    }, {
      threadId: threadId === THREAD_ID ? childA : childB,
      parentThreadId: threadId,
      name: threadId === THREAD_ID ? 'active A' : 'active B',
      agentName: 'coder',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      status: 'MODEL_STREAM',
      processing: true,
      turnCount: 1,
      toolCallCount: 0,
      outcome: null,
      updateTime: 1774958400,
    }])
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) =>
      snapshot(thread({ threadId, name: threadId === THREAD_ID ? 'thread A' : 'thread B' })),
    )
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: null,
      threadCount: 2,
    }])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([{
      threadId: threadB,
      parentThreadId: null,
      name: 'thread B',
      status: 'IDLE',
      processing: false,
      createdAt: null,
      updatedAt: null,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    }])
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    expect(await screen.findByRole('link', { name: /active A/ })).toBeInTheDocument()

    const composer = screen.getByRole('textbox', { name: '给 AI 发送消息' })
    await user.click(composer)
    await user.keyboard('/thread{Enter}')
    await user.click(await screen.findByRole('option', { name: /session/ }))
    await user.click(await screen.findByRole('option', { name: /thread B/ }))

    expect(await screen.findByRole('link', { name: /active B/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /active A/ })).not.toBeInTheDocument()
    expect(harnessService.getThreadTree).toHaveBeenCalledWith(threadB)
  })

  it('renames the bound Thread from the /rename-thread slash command', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.renameThread).mockImplementation(async (threadId, data) =>
      thread({ threadId, name: data.name, version: '1' }),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread({ name: 'old name' })))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/rename-thread{Enter}')

    await screen.findByRole('region', { name: '重命名 thread' })
    const input = screen.getByRole('textbox', { name: '名称' })
    expect(input).toHaveValue('old name')
    await user.clear(input)
    await user.type(input, 'slash renamed')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() =>
      expect(harnessService.renameThread).toHaveBeenCalledWith(THREAD_ID, { name: 'slash renamed' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 thread' })).not.toBeInTheDocument())
  })

  it('keeps the rename input and shows the error when a rename request fails', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.renameThread).mockRejectedValueOnce(new Error('rename rejected'))
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread()))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/rename-thread{Enter}')
    const input = await screen.findByRole('textbox', { name: '名称' })
    await user.clear(input)
    await user.type(input, 'keep me')
    await user.click(screen.getByRole('button', { name: '保存' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('rename rejected')
    expect(screen.getByRole('textbox', { name: '名称' })).toHaveValue('keep me')
    expect(screen.getByRole('region', { name: '重命名 thread' })).toBeInTheDocument()
  })

  it('renames the parent Session from a NEW_THREAD_DRAFT via /rename-session', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({
        kind: 'NEW_THREAD_DRAFT',
        sessionId: 'session-1',
        startEntryId: 'entry-1',
        threadName: 'branch-1',
      }),
    )
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([{
      entryId: 'entry-1',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    }])
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'original session',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'entry-1',
      threadCount: 1,
    }])
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/rename-session{Enter}')

    // rename-session 打开时按需拉取 owner Sessions 摘要并预填当前名称。
    const sessionInput = screen.getByRole('textbox', { name: '名称' })
    await waitFor(() => expect(sessionInput).toHaveValue('original session'))
    await user.clear(sessionInput)
    await user.type(sessionInput, 'renamed parent session')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(harnessService.renameSession).toHaveBeenCalledWith(
      'session-1',
      { name: 'renamed parent session' },
    ))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 Session' })).not.toBeInTheDocument())
  })

  it('consumes the canonical Session name returned by the server in the loaded picker cache', async () => {
    // 输入与返回不同：提交连续空白名称，服务端返回规范化后的权威名称；证明
    // 列表立即消费返回值（而非回显用户输入）。失效后的重查同样返回权威值，
    // 避免陈旧 mock 覆盖已确认的规范化名称。
    const user = userEvent.setup()
    let canonicalName = 'old session'
    vi.mocked(chatService.listChatSessions).mockImplementation(async () => [{
      sessionId: 'session-1',
      name: canonicalName,
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'preview',
      threadCount: 1,
    }])
    vi.mocked(harnessService.renameSession).mockImplementation(async (sessionId, data) => {
      canonicalName = data.name.replace(/\s+/gu, ' ').trim().toUpperCase()
      return {
        sessionId,
        name: canonicalName,
        createdAt: null,
      }
    })
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')

    // 进入 Session picker，从行内铅笔打开重命名面板（cache 已加载）。
    const sessionRow = await screen.findByRole('option', { name: /old session/ })
    const sessionRenameButton = sessionRow.parentElement?.querySelector('.thread-selection-rename')
    await user.click(sessionRenameButton as HTMLElement)
    const input = await screen.findByRole('textbox', { name: '名称' })
    expect(input).toHaveValue('old session')
    await user.clear(input)
    await user.type(input, '  renamed   session  ')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(harnessService.renameSession).toHaveBeenCalledWith(
      'session-1',
      { name: 'renamed session' },
    ))
    // 面板关闭返回 Session picker：行标题立即显示服务端权威规范化名称
    //（大写化 + 单空格），而不是用户输入的原始字符串。
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: /RENAMED SESSION/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /renamed {3}session/ })).not.toBeInTheDocument()
  })

  it('adopts the canonical Thread name returned by the server into the snapshot cache', async () => {
    // 输入与返回不同：提交连续空白名称，服务端返回规范化后的权威名称；证明面板
    // 立即采用响应值（patch snapshot cache）而非用户输入，且失效后的重查与
    // 再次打开都读到同一个权威名称。
    // 身份展示已上移 workspace 顶栏，因此这里直接断言快照缓存。
    const user = userEvent.setup()
    let canonicalName = 'ORIGINAL'
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.renameThread).mockImplementation(async (threadId, data) => {
      canonicalName = data.name.replace(/\s+/gu, ' ').trim().toUpperCase()
      return thread({ threadId, name: canonicalName, version: '1' })
    })
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async () =>
      snapshot(thread({ name: canonicalName })))
    const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    const cachedName = () => view.client.getQueryData<HarnessThreadSnapshotDTO>(
      queryKeys.threads.snapshot(THREAD_ID),
    )?.thread.name
    await waitFor(() => expect(cachedName()).toBe('ORIGINAL'))

    await user.click(composer)
    await user.keyboard('/rename-thread{Enter}')
    const input = await screen.findByRole('textbox', { name: '名称' })
    expect(input).toHaveValue('ORIGINAL')
    await user.clear(input)
    await user.type(input, '  new   thread name  ')
    await user.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(harnessService.renameThread).toHaveBeenCalledWith(
      THREAD_ID,
      { name: 'new thread name' },
    ))
    // 面板立即采用服务端规范化响应（patch cache，无需等待失效后的重新拉取）。
    await waitFor(() => expect(cachedName()).toBe('NEW THREAD NAME'))
    // 重新打开时预填的是权威名称，而不是用户输入或陈旧 mock。
    await user.click(composer)
    await user.keyboard('/rename-thread{Enter}')
    expect(await screen.findByRole('textbox', { name: '名称' })).toHaveValue('NEW THREAD NAME')
    expect(composer).toBeInTheDocument()
  })

  it('keeps the rename panel open while the rename PUT is in flight even on Escape', async () => {
    // 回归：PUT 在途时按 Escape 不得关闭面板（关闭会清空 renameTargetRef，
    // PUT 成功后跳过 cache patch 与失效，留下本地陈旧名称）；完成后仍需
    // 关闭并展示服务端权威名称。
    const user = userEvent.setup()
    let resolveRename: ((session: HarnessSessionDTO) => void) | null = null
    let canonicalName = 'session one'
    vi.mocked(chatService.listChatSessions).mockImplementation(async () => [{
      sessionId: 'session-1',
      name: canonicalName,
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'preview',
      threadCount: 1,
    }])
    vi.mocked(harnessService.renameSession).mockImplementation(
      async () => new Promise<HarnessSessionDTO>((resolve) => {
        resolveRename = (session) => resolve(session)
      }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')

    const sessionRow = await screen.findByRole('option', { name: /session one/ })
    const sessionRenameButton = sessionRow.parentElement?.querySelector('.thread-selection-rename')
    await user.click(sessionRenameButton as HTMLElement)
    const input = await screen.findByRole('textbox', { name: '名称' })
    await user.clear(input)
    await user.type(input, 'renamed session')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(harnessService.renameSession).toHaveBeenCalled())

    // PUT 在途：Escape 与关闭按钮都无效，面板保持打开。
    await user.keyboard('{Escape}')
    expect(screen.getByRole('region', { name: '重命名 Session' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled()

    // PUT 返回权威名称：面板关闭、picker 行立即展示服务端规范化名称。
    canonicalName = 'RENAMED SESSION'
    resolveRename?.({ sessionId: 'session-1', name: canonicalName, createdAt: null })
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: '重命名 Session' })).not.toBeInTheDocument())
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: /RENAMED SESSION/ })).toBeInTheDocument()
  })

  it('renames a Session row from the Session picker and a Thread row from the Thread picker', async () => {
    const user = userEvent.setup()
    vi.mocked(chatService.listChatSessions).mockResolvedValue([{
      sessionId: 'session-1',
      name: 'session one',
      createdAt: null,
      lastActivityAt: null,
      firstMessagePreview: 'preview',
      threadCount: 1,
    }])
    vi.mocked(harnessService.listSessionThreads).mockResolvedValue([{
      threadId: THREAD_ID,
      parentThreadId: null,
      name: 'thread one',
      createdAt: null,
      updatedAt: null,
      status: 'IDLE',
      processing: false,
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      headMessagePreview: null,
    }])
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/thread{Enter}')

    // Session 行铅笔 -> 预填的 Session 重命名面板。
    const sessionRow = await screen.findByRole('option', { name: /session one/ })
    const sessionRenameButton = sessionRow.parentElement?.querySelector('.thread-selection-rename')
    expect(sessionRenameButton).not.toBeNull()
    await user.click(sessionRenameButton as HTMLElement)
    expect(await screen.findByRole('textbox', { name: '名称' })).toHaveValue('session one')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(harnessService.renameSession).toHaveBeenCalledWith(
      'session-1',
      { name: 'session one' },
    ))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 Session' })).not.toBeInTheDocument())
    // 会话选择面板重新展示（返回原交互）。
    expect(await screen.findByRole('region', { name: '选择 Session' })).toBeInTheDocument()

    await user.click(await screen.findByRole('option', { name: /session one/ }))
    const threadRow = await screen.findByRole('option', { name: /thread one/ })
    const threadRenameButton = threadRow.parentElement?.querySelector('.thread-selection-rename')
    expect(threadRenameButton).not.toBeNull()
    await user.click(threadRenameButton as HTMLElement)
    const threadInput = await screen.findByRole('textbox', { name: '名称' })
    expect(threadInput).toHaveValue('thread one')
    await user.clear(threadInput)
    await user.type(threadInput, 'thread renamed')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(harnessService.renameThread).toHaveBeenCalledWith(
      THREAD_ID,
      { name: 'thread renamed' },
    ))
    await waitFor(() => expect(screen.queryByRole('region', { name: '重命名 thread' })).not.toBeInTheDocument())
    expect(await screen.findByRole('region', { name: '选择 Thread' })).toBeInTheDocument()
  })

  it('keeps the composer editable with queued commands while blocking target switching', async () => {
    // QUEUED USER_MESSAGE 只保留在 hasPendingOperation 栅栏中：Composer 仍可
    // 编辑并提交新 batch，/thread 切换则必须被拒绝。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(
      thread(),
      {
        queuedCommands: [{
          threadId: THREAD_ID,
          sequence: '1',
          type: 'USER_MESSAGE',
          state: 'QUEUED',
          idempotencyKey: 'queued-1',
          payloadJson: JSON.stringify({
            message: { role: 'USER', contents: [{ type: 'text', text: '排队中' }] },
          }),
          cancelledAt: null,
          createTime: null,
        }],
      },
    ))
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.controller.queuedCommands).toHaveLength(1))

    expect(hook.result.current.composer.disabled).toBe(false)
    expect(hook.result.current.composer.pending).toBe(false)

    act(() => hook.result.current.composer.onCommand(testCommand('thread')))
    expect(hook.result.current.interaction).toBeNull()
    expect(hook.result.current.error).toContain('等待完成或重试原操作')

    act(() => hook.result.current.composer.onSubmit([createTextPart('下一条消息')]))
    await waitFor(() =>
      expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledWith(
        THREAD_ID,
        expect.objectContaining({
          expectedHeadEntryId: 'head-1',
          expectedNextCommandSequence: '1',
          commands: [expect.objectContaining({ type: 'USER_MESSAGE' })],
        }),
      ),
    )
    // 既有 Thread 绝不走创建入口
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it('keeps the stored draft unchanged while navigating recalled history', async () => {
    // 历史导航只更新当前受控内容；Thread 草稿记录仍是用户原始编辑（刷新按该记录恢复）。
    await saveThreadDraftParts(THREAD_ID, [createTextPart('original draft')], [])
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.composer.parts).toEqual([
      expect.objectContaining({ type: 'text', text: 'original draft' }),
    ]))

    act(() =>
      hook.result.current.composer.onHistoryPartsChange?.([
        createTextPart('recalled history'),
      ]),
    )

    expect(hook.result.current.composer.parts).toEqual([
      expect.objectContaining({ type: 'text', text: 'recalled history' }),
    ])
    expect((await loadThreadDraft(THREAD_ID))?.parts).toEqual([
      expect.objectContaining({ type: 'text', text: 'original draft' }),
    ])
  })

  it('does not retain a background subscription for a terminal previous thread', async () => {
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ status: 'IDLE', processing: false })),
    )
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/new{Enter}')
    await waitFor(() => expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain('NEW_SESSION_DRAFT'))
  })

  it('fails closed when no catalog agent can materialize a new draft', async () => {
    const hook = renderController({ agents: [] })
    await waitFor(() => expect(hook.result.current.activeDraft).toBeNull())
    expect(hook.result.current.composer.disabled).toBe(true)
    act(() => hook.result.current.requestBranchFromEntry({
      entryId: 'entry-without-draft',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    }))
  })

  it('reports a catalog agent that the bound branch panel cannot resolve', async () => {
    const extraAgent = { ...agents[0]!, name: 'other' }
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController({ agents: [...agents, extraAgent] })
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.selectAgent('other'))
    expect(hook.result.current.error).toBeTruthy()
  })

  it('routes bound draft controls through the bound branch panel', async () => {
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.selectAgent('assistant'))
    act(() => hook.result.current.composer.settings?.onModelChange({
      providerName: 'minimax',
      modelName: 'MiniMax',
      variant: 'default',
    }))
    act(() => hook.result.current.composer.settings?.onYoloChange(true))
    expect(hook.result.current.target.kind).toBe('BOUND_THREAD')
  })

  it('surfaces a bound stop failure through the controller action error', async () => {
    vi.mocked(harnessService.stopThread).mockRejectedValueOnce(new Error('stop failed'))
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController()
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.composer.onCommand(testCommand('stop')))
    await waitFor(() => expect(hook.result.current.error).toBe('stop failed'))
  })

  it('treats a STOPPED bound thread as not working and keeps the tree-wide Stop command available', async () => {
    // 测试意图：本地 STOPPED 既不进入工作态（不展示工作条），也不得禁用 /stop：
    // Stop 是整棵执行树的控制面，本地已静止不代表没有活跃后代需要停止。
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(threadFixture(THREAD_ID, {
        status: 'STOPPED',
        processing: false,
        executionControl: 'STOPPED',
      })),
    )
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalled())

    // STOPPED 不是工作态：不展示任何工作条
    expect(document.querySelector('.thread-working')).toBeNull()

    await user.click(composer)
    await user.keyboard('/stop')
    expect(await screen.findByRole('option', { name: /^stop/ })).toHaveAttribute('aria-disabled', 'false')
  })

  it('keeps a failed draft restore visible with a manual retry that restores the cancelled input', async () => {
    // 测试意图：Stop 已成功但取消的输入没能回到输入框时，必须给出可见的失败提示与手动重试入口；
    // 重试成功后回填内容进入输入框，提示消失。
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(threadFixture(THREAD_ID, { executionControl: 'RUNNABLE' })),
    )
    vi.mocked(harnessService.stopThread).mockResolvedValueOnce({
      status: 'STOPPED',
      thread: thread({ executionControl: 'STOPPED' }),
      stoppedThreads: [
        {
          threadId: THREAD_ID,
          stopRequestId: 'stop-restore-1',
          stoppedTurnEndEntryId: null,
          cancelledCommandCount: 1,
          cancelledInputs: [
            {
              sequence: '1',
              idempotencyKey: 'c1',
              type: 'USER_MESSAGE',
              payloadJson:
                '{"message":{"role":"USER","contents":[{"type":"text","text":"cancelled draft"}]}}',
            },
          ],
        },
      ],
    } as HarnessThreadStopResultDTO)
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    // 存储事务失败：回执合并无法落盘，草稿恢复必然失败。
    Object.defineProperty(window, 'indexedDB', {
      configurable: true,
      writable: true,
      value: createMockIDBFactory({ shouldFailTransaction: true }),
    })

    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/stop{Enter}')

    const retry = await screen.findByRole('button', { name: '重试恢复草稿' })
    expect(screen.getByText('未消费的输入未能恢复到编辑区，请重试')).toBeInTheDocument()

    // 存储恢复后手动重试：取消的输入回到输入框，提示消失。
    Object.defineProperty(window, 'indexedDB', {
      configurable: true,
      writable: true,
      value: createMockIDBFactory(),
    })
    await user.click(retry)
    await waitFor(() =>
      expect(screen.queryByTestId('draft-restore-retry')).not.toBeInTheDocument())
    await waitFor(() => expect(composer).toHaveTextContent('cancelled draft'))
  })

  it('renders localized QUEUED status label when bound thread is queued', async () => {
    // 测试意图：当绑定的 Thread 处于 QUEUED 排队状态时，AgentPane 必须将 workingLabel 传给 ChatPanel 并展示“排队中”。
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(threadFixture(THREAD_ID, { status: 'QUEUED', processing: false })),
    )
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await waitFor(() => {
      expect(screen.getByText('排队中')).toBeInTheDocument()
    })
  })

  it('isolates ISSUE_AGENT owner storage and routing from independent thread pane without crosstalk', async () => {
    // 测试意图：验证当 owner 为 ISSUE_AGENT 时，其生命周期与无 owner 的子线程路由或 CHAT owner 完全隔离，
    // 不会向 CHAT 存储命名空间写入 target，也不会调用 chat 专有服务，两套架构 owner 语义不混淆。
    const issueOwner: AgentRuntimeOwnerDTO = {
      type: 'ISSUE_AGENT',
      issueId: 'issue-42',
      agentName: 'architect',
    }
    const issueThreadId = '00000000-0000-0000-0000-000000000042'
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(threadFixture(issueThreadId, { name: 'Architect Thread' })),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })

    render(
      <QueryClientProvider client={client}>
        <AgentPane
          owner={issueOwner}
          paneId="pane-issue"
          agents={agents}
          initialTarget={{ kind: 'BOUND_THREAD', threadId: issueThreadId }}
          focused
        />
      </QueryClientProvider>,
    )

    // 面板不再自渲染执行根身份；Composer 挂载即证明面板就绪，同时断言没有任何
    // CHAT 命名空间的读写（owner 语义不串台）。
    await screen.findByLabelText('给 AI 发送消息')

    expect(chatService.listChatSessions).not.toHaveBeenCalled()
    expect(localStorage.getItem('kk-studio.agent-pane-target.CHAT:chat-1:pane-issue')).toBeNull()
  })
})

function threadFixture(threadId: string, overrides: Partial<HarnessThreadDTO> = {}) {
  return thread({ threadId, ...overrides })
}

function renderPane(
  owner: { type: 'CHAT'; chatId: string },
  environments: EnvironmentCardDTO[] = [],
  validateDraftName: ((target: PaneTarget, name: string) => Promise<string | null>) | null = async () => null,
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const result = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AgentPane
          owner={owner}
          paneId="pane-1"
          agents={agents}
          environments={environments}
          focused
          onValidateDraftName={validateDraftName ?? undefined}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...result, client }
}

/**
 * 控制面探针：按父面板的结构提供绑定目标与唯一投影（控制 Hook 不再自建目标与订阅）。
 */
function useRootControlProbe(
  options: Omit<UseRootThreadControlOptions, 'target' | 'setTarget' | 'projection'>,
) {
  const paneTarget = usePaneTarget({
    owner: options.owner,
    paneId: options.paneId,
    initialTarget: options.initialTarget,
  })
  const projection = useThreadProjection(
    isBoundTarget(paneTarget.target) ? paneTarget.target.threadId : '',
  )
  return useRootThreadControl({
    ...options,
    target: paneTarget.target,
    setTarget: paneTarget.setTarget,
    projection,
  })
}

function renderController({
  agents: controllerAgents = agents,
  owner = { type: 'CHAT' as const, chatId: CHAT_ID },
  onRequestBranch,
}: {
  agents?: typeof agents
  owner?: { type: 'CHAT'; chatId: string }
  onRequestBranch?: (request: { sessionId: string; startEntryId: string; sourceLabel: string | null }) => void
} = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return renderHook(() => useRootControlProbe({
    owner,
    paneId: 'probe',
    agents: controllerAgents,
    environments: [],
    defaults: {},
    focused: true,
    onRequestBranch,
  }), {
    wrapper: ({ children }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  })
}

function testCommand(
  id: ThreadCommand['id'] | 'disabled',
  overrides: Partial<ThreadCommand> = {},
): ThreadCommand {
  return {
    id: id as ThreadCommand['id'],
    label: id,
    description: '',
    ...overrides,
  }
}

describe('TURN_END 绑定与 Debug 只读退出', () => {
  function bindPaneTarget() {
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
  }

  function modelRequestDebug(): HarnessModelRequestDebugDTO {
    return {
      kind: 'NEXT_REQUEST_PREVIEW',
      generatedAt: '2026-09-27T05:00:00Z',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      environmentName: null,
      systemInstruction: 'system prompt',
      tools: [],
      skills: [],
      subagents: [],
      cacheControl: null,
      planningError: null,
      frozenInvocation: null,
    }
  }

  function mockDebugProjection() {
    vi.mocked(harnessService.getModelRequestDebug).mockResolvedValue(modelRequestDebug())
  }

  function renderPane(environments: EnvironmentCardDTO[] = []) {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const result = render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <AgentPane
            owner={{ type: 'CHAT', chatId: CHAT_ID }}
            paneId="pane-1"
            agents={agents}
            environments={environments}
            focused
          />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    return { ...result, client }
  }

  it('binds the TURN_END footer entry id as the new branch start entry', async () => {
    // /history 的“从此处分支”与回合 footer 共用同一条绑定：传入的是真实 TURN_END Entry id，
    // 作为新分支的 startEntryId；本步骤不写任何目标、不发起任何请求。
    const onRequestBranch = vi.fn()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    const hook = renderController({ onRequestBranch })
    await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
    act(() => hook.result.current.requestBranchFromEntry({
      entryId: 'entry-turn-end-2',
      sessionId: 'session-1',
      parentEntryId: 'entry-1',
      entryType: 'TURN_END',
      payloadJson: JSON.stringify({ outcome: 'COMPLETED' }),
      createTime: null,
    }))
    // 关键契约：传下去的是 TURN_END Entry id（sourceLabel 是绑定分支名，身份未加载时为 null）。
    expect(onRequestBranch).toHaveBeenCalledWith(expect.objectContaining({
      sessionId: 'session-1',
      startEntryId: 'entry-turn-end-2',
      sourceLabel: 'thread-name',
    }))
    // 请求只向上传递：目标与本地草稿不变，也没有请求发出。
    expect(hook.result.current.target).toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
    expect(harnessService.acceptThreadCommandBatch).not.toHaveBeenCalled()
  })

  it('opens a local FORK_SESSION_DRAFT from a closed boundary in HistoryTree and submits NEW_FORKED_SESSION idempotently across retries', async () => {
    const user = userEvent.setup()
    bindPaneTarget()
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([
      {
        entryId: 'entry-root',
        sessionId: 'session-1',
        parentEntryId: null,
        entryType: 'ROOT',
        payloadJson: JSON.stringify({
          settings: {
            agentName: 'assistant',
            model: { providerName: 'provider-1', modelName: 'model-1', variant: 'default' },
            environmentName: null,
          },
        }),
        createTime: null,
      },
      {
        entryId: 'entry-user-1',
        sessionId: 'session-1',
        parentEntryId: 'entry-root',
        entryType: 'MESSAGE',
        payloadJson: JSON.stringify({
          message: { role: 'USER', contents: [{ type: 'text', text: 'first prompt' }] },
        }),
        createTime: null,
      },
      {
        entryId: 'entry-turn-end-1',
        sessionId: 'session-1',
        parentEntryId: 'entry-user-1',
        entryType: 'TURN_END',
        payloadJson: JSON.stringify({ turnStartEntryId: 'entry-root', outcome: 'COMPLETED', continueModel: false }),
        createTime: null,
      },
    ])
    renderPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/history{Enter}')

    const rows = await waitFor(() => {
      const found = document.querySelectorAll('.history-tree-entry')
      expect(found).toHaveLength(3)
      return found
    })
    const forkSessionButton = screen.getByRole('button', { name: '从此处新建会话' })
    const branchButton = screen.getByRole('button', { name: '从此处分支' })

    // 非闭合边界（MESSAGE 行）两个分叉动作都必须禁用。
    await user.click(rows[1] as HTMLElement)
    expect(rows[1]?.getAttribute('data-can-fork')).toBe('false')
    expect(forkSessionButton).toBeDisabled()
    expect(branchButton).toBeDisabled()

    // 选中已关闭 TURN_END：点击“从此处新建会话”只切本地 FORK_SESSION_DRAFT，不预创建 Session/Thread。
    await user.click(rows[2] as HTMLElement)
    expect(rows[2]?.getAttribute('data-can-fork')).toBe('true')
    expect(forkSessionButton).toBeEnabled()
    await user.click(forkSessionButton)

    const targetKey = `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`
    expect(JSON.parse(localStorage.getItem(targetKey) ?? 'null')).toEqual({
      kind: 'FORK_SESSION_DRAFT',
      sessionId: 'session-1',
      sourceThreadId: THREAD_ID,
      startEntryId: 'entry-turn-end-1',
    })
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()

    // 首次发送遇到网络超时（未知结果），重试必须复用同一组客户端预分配的 sessionId/threadId 与命令幂等键。
    vi.mocked(harnessService.acceptCommandBatch)
      .mockRejectedValueOnce(new Error('network timeout'))
      .mockResolvedValueOnce({
        sessionId: 'forked-session-1',
        thread: thread({
          threadId: 'forked-thread-1',
          sessionId: 'forked-session-1',
          name: 'main',
        }),
        acceptedCommands: [],
      })

    const draftComposer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(draftComposer)
    await user.type(draftComposer, 'hello forked session{Enter}')

    const retryButton = await screen.findByRole('button', { name: '重试' })
    expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1)
    const firstRequest = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]?.[0]
    expect(firstRequest?.owner).toEqual({ type: 'CHAT', chatId: CHAT_ID })
    expect(firstRequest?.target).toMatchObject({
      type: 'NEW_FORKED_SESSION',
      sourceThreadId: THREAD_ID,
      startEntryId: 'entry-turn-end-1',
      yoloEnabled: false,
    })

    await user.click(retryButton)
    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(2))
    const secondRequest = vi.mocked(harnessService.acceptCommandBatch).mock.calls[1]?.[0]
    expect(secondRequest).toEqual(firstRequest)
    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem(targetKey) ?? 'null')).toEqual({
        kind: 'BOUND_THREAD',
        threadId: 'forked-thread-1',
      })
    })
  })

  it('returns from Debug through the read-only toolbar and keeps the draft and binding', async () => {
    // Debug 覆盖整个 pane 且底部控制区被隐藏，返回必须由 Debug 自身的工具条提供。
    const user = userEvent.setup()
    bindPaneTarget()
    mockDebugProjection()
    renderPane()
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.type(composer, 'draft kept across debug')
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    await user.click(screen.getByRole('option', { name: /debug/ }))
    await screen.findByRole('listbox', { name: '事件' })
    expect(document.querySelector('.thread-debug-back')).toBeNull()
    expect(screen.queryByRole('textbox', { name: '给 AI 发送消息' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '关闭 Debug', exact: true }))

    // 回到会话视图：Debug chrome 与事件列表消失，草稿、pane 绑定原地保留。
    expect(document.querySelector('.thread-debug-back')).toBeNull()
    expect(screen.queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument()
    const conversation = await screen.findByLabelText('给 AI 发送消息')
    expect(conversation).toHaveTextContent('draft kept across debug')
    expect(localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )).toContain(THREAD_ID)
  })
})

describe('branchDraftFromEntry and branchDraftFromEntryPath environment replay', () => {
  const fallbackDraft: BranchDraft = {
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    environmentName: 'initial-env',
    yoloEnabled: false,
  }

  function createEntry(payload: Record<string, unknown>, entryId = 'e1', parentEntryId: string | null = null): HarnessSessionEntryDTO {
    return {
      entryId,
      sessionId: 's1',
      parentEntryId,
      entryType: 'MESSAGE',
      payloadJson: JSON.stringify(payload),
      createTime: '2026-01-01T00:00:00Z',
    }
  }

  /**
   * 测试意图：验证 branchDraftFromEntry 对 settings.environmentName 的重放防御规则：
   * 1. 文本值选中该环境；
   * 2. 显式 null 清除该环境；
   * 3. 缺少该键时保留 fallback 环境；
   * 4. 非字符串非空（如数值、对象、undefined）等不可信载荷保留 fallback 环境。
   */
  it('handles environmentName correctly on single entry replay', () => {
    // 文本值选中
    const selected = branchDraftFromEntry(
      createEntry({ settings: { environmentName: 'custom-env' } }),
      fallbackDraft,
    )
    expect(selected?.environmentName).toBe('custom-env')

    // 显式 null 清除
    const cleared = branchDraftFromEntry(
      createEntry({ settings: { environmentName: null } }),
      fallbackDraft,
    )
    expect(cleared?.environmentName).toBeNull()

    // 缺少 environmentName 键：保留 fallback
    const missingKey = branchDraftFromEntry(
      createEntry({ settings: { agentName: 'coder' } }),
      fallbackDraft,
    )
    expect(missingKey?.environmentName).toBe('initial-env')

    // 畸形/不可信载荷：保留 fallback
    for (const malformed of [123, true, {}, []]) {
      const result = branchDraftFromEntry(
        createEntry({ settings: { environmentName: malformed } }),
        fallbackDraft,
      )
      expect(result?.environmentName).toBe('initial-env')
    }
  })

  /**
   * 测试意图：验证 branchDraftFromEntryPath 沿 entry 祖先链依序回放时，environmentName 的变更与清除能够正确链式传递。
   */
  it('replays environment changes sequentially along the entry path', () => {
    const entries: HarnessSessionEntryDTO[] = [
      { ...createEntry({ settings: { agentName: 'a1', environmentName: 'env-1' } }, 'root', null), entryType: 'ROOT' },
      createEntry({ settings: { agentName: 'a2' } }, 'child-1', 'root'),
      createEntry({ settings: { environmentName: null } }, 'child-2', 'child-1'),
    ]

    // 到 root：环境为 env-1
    const atRoot = branchDraftFromEntryPath(entries, 'root', fallbackDraft)
    expect(atRoot?.environmentName).toBe('env-1')

    // 到 child-1：未改动环境，继承 env-1
    const atChild1 = branchDraftFromEntryPath(entries, 'child-1', fallbackDraft)
    expect(atChild1?.environmentName).toBe('env-1')

    // 到 child-2：显式 null，环境被清空
    const atChild2 = branchDraftFromEntryPath(entries, 'child-2', fallbackDraft)
    expect(atChild2?.environmentName).toBeNull()
  })

  /**
   * 测试意图：验证 unbound footer 按 activeDraft.environmentName 匹配卡片，
   * 未知 name 显示 unavailable 而不是 none。
   */
  it('projects unbound environment status from the active draft', async () => {
    const envCard: EnvironmentCardDTO = {
      id: 'uuid-env-ready',
      name: 'cluster-ready',
      status: 'READY',
      ready: true,
      lastSeen: '2026-09-19T00:00:00.000Z',
      capabilities: [],
      userName: null,
      homeDirectory: null,
      version: '1',
      createTime: '2026-09-19T00:00:00.000Z',
      updateTime: '2026-09-19T00:00:00.000Z',
    }

    // 1. unbound 面板：未知环境名称显示 unavailable
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const { result: unboundResult } = renderHook(
      () =>
        useRootControlProbe({
          owner: { type: 'CHAT', chatId: CHAT_ID },
          paneId: 'p1',
          target: { kind: 'NEW_SESSION_DRAFT' },
          agents,
          environments: [envCard],
          defaults: {},
        }),
      {
        wrapper: ({ children }) => (
          <QueryClientProvider client={client}>{children}</QueryClientProvider>
        ),
      },
    )

    await waitFor(() => expect(unboundResult.current.activeDraft).not.toBeNull())

    // 本地草稿切换为已知环境
    act(() => {
      unboundResult.current.composer.settings?.onEnvironmentChange('cluster-ready')
    })
    expect(unboundResult.current.boundEnvironment?.name).toBe('cluster-ready')
    expect(unboundResult.current.environmentReady).toBe(true)

    // 本地草稿切换为未知环境：不可用且标记 false
    act(() => {
      unboundResult.current.composer.settings?.onEnvironmentChange('unknown-env')
    })
    expect(unboundResult.current.boundEnvironment?.name).toBe('unknown-env')
    expect(unboundResult.current.environmentReady).toBe(false)

    // 本地草稿清空为 None
    act(() => {
      unboundResult.current.composer.settings?.onEnvironmentChange(null)
    })
    expect(unboundResult.current.boundEnvironment).toBeNull()
    expect(unboundResult.current.environmentReady).toBeUndefined()
  })

  it('carries composer attachments on an owner-free bound thread through the generic command batch', async () => {
    // 测试意图：宿主不再有受控指令回调；无 owner 的既有 Thread 提交含附件的草稿时，
    // 附件随 USER_MESSAGE 命令批次原样发出，既不静默丢弃也不落到创建入口。
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(
      () =>
        useRootControlProbe({
          paneId: THREAD_ID,
          agents,
          environments: [],
          defaults: {},
          focused: false,
        }),
      {
        wrapper: ({ children }) => (
          <QueryClientProvider client={client}>{children}</QueryClientProvider>
        ),
      },
    )
    await waitFor(() => expect(result.current.composer.disabled).toBe(false))

    await act(async () => {
      result.current.composer.onSubmit([
        { type: 'text', partId: 'p-1', text: 'Here is document' },
        { type: 'attachment', partId: 'p-2', uploadId: 'up-123', filename: 'doc.pdf' },
      ])
    })

    await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
    const [threadId, request] = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]!
    expect(threadId).toBe(THREAD_ID)
    expect(request.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
    const contents = (request.commands[0] as { contents: Array<Record<string, unknown>> }).contents
    expect(contents.find((content) => content.type === 'ATTACHMENT')).toMatchObject({
      type: 'ATTACHMENT',
      uploadId: 'up-123',
    })
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
  })

  it('keeps container creation and unready preview inert when an owner-free pane is driven by callbacks', async () => {
    // 测试意图：绕开按钮直接调用回调时，Issue 容器面板也不能创建 Session 或预览未就绪草稿；
    // 容器创建仍属于 owner 范围，既有 Thread 的写入完全由通用交互承担。
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useRootControlProbe({
      paneId: THREAD_ID,
      agents,
      environments: [],
      defaults: {},
      focused: false,
    }), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    })
    await waitFor(() => expect(result.current.composer.disabled).toBe(false))

    act(() => {
      void result.current.handlePreview()
      result.current.composer.onCommand(testCommand('new'))
    })

    expect(result.current.target).toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
    // 空草稿没有可预览内容：按钮禁用，且绝不发出预览请求。
    expect(result.current.previewDisabled).toBe(true)
    expect(result.current.previewDisabledReason).toBe('草稿为空')
    expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
  })

/** Debug 视图的结构化投影：就绪后提供「预览当前草稿」操作。 */
function modelRequestDebug(): HarnessModelRequestDebugDTO {
  return {
    kind: 'NEXT_REQUEST_PREVIEW',
    generatedAt: '2026-09-27T05:00:00Z',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    environmentName: null,
    systemInstruction: 'system prompt',
    tools: [],
    skills: [],
    subagents: [],
    cacheControl: null,
    planningError: null,
    frozenInvocation: null,
  }
}

function mockDebugProjection() {
  vi.mocked(harnessService.getModelRequestDebug).mockResolvedValue(modelRequestDebug())
}

/** 预览入口只属于绑定 Thread 的 Debug 视图，因此必须先落到 BOUND_THREAD 目标。 */
function bindPaneTarget() {
  localStorage.setItem(
    `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
  )
}

/** 用真实的 /debug 斜杠命令切到 Debug 视图，返回仍挂载的 composer。 */
async function openDebugView(user: ReturnType<typeof userEvent.setup>) {
  const composer = await screen.findByLabelText('给 AI 发送消息')
  await user.click(composer)
  await user.keyboard('/debug{Enter}')
  await screen.findByRole('listbox', { name: '事件' })
  return composer
}

/**
 * Thread 快照读取闸门：默认持续提供面板已见的旧快照；
 * serveFresh() 之后的每次读取（含预览前的 fresh GET）改由用例决定，可返回推进后的快照或悬挂的 Promise。
 */
function snapshotGate(initial: HarnessThreadSnapshotDTO = snapshot()) {
  let fresh: (() => Promise<HarnessThreadSnapshotDTO>) | null = null
  vi.mocked(harnessService.getThreadSnapshot)
    .mockImplementation(async () => (fresh ? fresh() : initial))
  return (serve: () => Promise<HarnessThreadSnapshotDTO>) => {
    fresh = serve
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((complete, fail) => {
    resolve = complete
    reject = fail
  })
  return { promise, resolve, reject }
}

function previewResponse(overrides: Partial<ProviderRequestPreviewDTO> = {}): ProviderRequestPreviewDTO {
  return {
    kind: 'DRAFT_REQUEST_PREVIEW',
    providerType: 'OPENAI',
    modelName: 'MiniMax',
    bodyByteSize: 120,
    bodyJson: '{"messages":[{"role":"user","content":"draft"}]}',
    sourceHeadEntryId: 'head-1',
    generatedAt: '2026-09-27T05:00:00Z',
    ...overrides,
  }
}

function queuedCommand(): HarnessThreadCommandDTO {
  return {
    threadId: THREAD_ID,
    sequence: '1',
    type: 'USER_MESSAGE',
    state: 'QUEUED',
    idempotencyKey: 'cmd-queued-1',
    payloadJson: '{}',
    cancelledAt: null,
    createTime: '2026-09-27T05:00:00Z',
  }
}

function activeModelInvocation(): ModelInvocationDTO {
  return {
    id: 'inv-1',
    threadId: THREAD_ID,
    turnStartEntryId: 'turn-1',
    requestHeadEntryId: 'head-1',
    status: 'STREAMING',
    attempt: 1,
    streamCheckpointJson: null,
    resultJson: null,
    errorJson: null,
    resultEntryId: null,
    createTime: '2026-09-27T05:00:00Z',
    updateTime: '2026-09-27T05:00:01Z',
  }
}

function activeToolInvocation(): ToolInvocationDTO {
  return {
    id: 'tool-1',
    modelInvocationId: 'inv-1',
    assistantEntryId: 'assistant-1',
    callIndex: 0,
    status: 'RUNNING',
    attempt: 1,
    toolCallId: 'call-1',
    toolName: 'read',
    rendererKey: 'tool',
    environmentId: null,
    requiredEnvironmentId: null,
    waitingForEnvironment: false,
    requiredEnvironmentName: null,
    environmentWaitFreshnessAt: null,
    argumentsJson: '{}',
    approvalJson: null,
    resultJson: null,
    errorJson: null,
    createTime: '2026-09-27T05:00:00Z',
    updateTime: '2026-09-27T05:00:01Z',
  }
}

/** 预览失败同时投影到 Debug 预览区与活动错误区，按文案断言以免依赖提示条数。 */
async function expectAlertText(text: string) {
  await waitFor(() => {
    expect(screen.getAllByRole('alert').some((node) => node.textContent?.includes(text))).toBe(true)
  })
}

function invokeDraftPreview() {
  act(() => {
    void latestControlRef.current?.handlePreview()
  })
}

/**
 * 触发预览并确认预览前的 fresh GET 真的发出去了。
 * 之后的“不得 POST”断言因此不会因为流程根本没启动而空转通过。
 */
async function clickPreviewAndAwaitFreshGet(
  _user?: ReturnType<typeof userEvent.setup>,
  _trigger?: HTMLElement,
) {
  const before = vi.mocked(harnessService.getThreadSnapshot).mock.calls.length
  invokeDraftPreview()
  await waitFor(() =>
    expect(vi.mocked(harnessService.getThreadSnapshot).mock.calls.length).toBeGreaterThan(before))
}

/** fakeStorage 分配的服务端 upload 句柄；草稿里的客户端 localId 永远不等于它。 */
const SERVER_UPLOAD_ID = '99999999-8888-4777-8666-555555555555'

/**
 * 真实组件默认用 Web Worker 算 sha256（jsdom 无 Worker，且会拉真实模块资源）。
 * 这里用同协议的内存 Worker 顶替，保留 hashFile 的调用路径。
 */
function stubHashWorker() {
  class FakeHashWorker {
    private listeners: Record<string, ((event: unknown) => void)[]> = {}
    addEventListener(type: string, listener: (event: unknown) => void) {
      this.listeners[type] = [...(this.listeners[type] ?? []), listener]
    }
    removeEventListener(type: string, listener: (event: unknown) => void) {
      this.listeners[type] = (this.listeners[type] ?? []).filter((fn) => fn !== listener)
    }
    postMessage(data: { requestId: string }) {
      for (const listener of this.listeners.message ?? []) {
        listener({ data: { requestId: data.requestId, sha256: 'a'.repeat(64) } })
      }
    }
    terminate() {}
  }
  vi.stubGlobal('Worker', FakeHashWorker)
}

/** 通过 Composer 的隐藏 file input 走真实上传管线（hash → reserve → put → complete）。 */
async function uploadAttachment(
  user: ReturnType<typeof userEvent.setup>,
  file: File,
): Promise<string> {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]')
  expect(input).not.toBeNull()
  await user.upload(input!, file)
  await waitFor(() => expect(fakeStorage.completeUpload).toHaveBeenCalledWith(SERVER_UPLOAD_ID))
  const pill = document.querySelector<HTMLElement>('[data-part-type="attachment"]')
  return pill?.dataset.uploadId ?? ''
}

/** 用真实的 Composer 权限菜单改变 branch draft 设置，消息草稿一个字都不动。 */
async function toggleYolo(user: ReturnType<typeof userEvent.setup>) {
  const closeDebug = screen.queryByRole('button', { name: '关闭 Debug', exact: true })
  if (closeDebug) {
    // 设置不属于 Debug；退出后修改，迟到预览仍必须被原身份门禁拦截。
    await user.click(closeDebug)
  }
  await user.click(screen.getByRole('button', { name: '权限模式' }))
  await user.click(await screen.findByRole('option', { name: 'YOLO' }))
  // 权限控件读的就是 activeDraft：文案翻转即证明 branch draft 真的变了
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '权限模式' })).toHaveTextContent('YOLO'))
}

describe('previewProviderRequest in AgentPane / useAgentPaneController', () => {
    it('enables the Debug preview trigger for an owner-free bound thread', async () => {
      // 测试意图：Issue 容器不再封锁预览；无 owner 的既有 Thread 在 Debug 视图下，
      // 标题按钮已移除；底层的 preview readiness 随草稿就绪，并真实发出 per-thread 预览请求（请求体无 owner）。
      const user = userEvent.setup()
      mockDebugProjection()
      vi.mocked(harnessService.previewProviderRequest).mockResolvedValueOnce(previewResponse({
        bodyJson: '{"messages":[{"role":"user","content":"preview owner-free"}]}',
      }))
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      render(
        <QueryClientProvider client={client}>
          <AgentPane paneId={THREAD_ID} agents={agents} environments={[]} focused />
        </QueryClientProvider>,
      )
      const composer = await openDebugView(user)
      expect(screen.queryByRole('button', { name: /预览当前草稿/ })).not.toBeInTheDocument()
      expect(latestControlRef.current?.previewDisabled).toBe(true)
      expect(latestControlRef.current?.previewDisabledReason).toBe('草稿为空')

      await user.click(composer)
      await user.type(composer, 'preview owner-free')
      await waitFor(() => expect(latestControlRef.current?.previewDisabled).toBe(false))
      invokeDraftPreview()

      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      const [threadId, request] = vi.mocked(harnessService.previewProviderRequest).mock.calls[0]!
      expect(threadId).toBe(THREAD_ID)
      expect(request).not.toHaveProperty('owner')
      expect(request).not.toHaveProperty('target')
    })

    it('hides the Debug preview trigger for a new session draft target', async () => {
      // 测试意图：新建草稿没有可预览的绑定 Thread、也不是会话内分支草稿，
      // 因此既进不了 Debug 视图，也没有任何预览入口。
      const user = userEvent.setup()
      mockDebugProjection()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.type(composer, 'no bound thread yet')
      await user.click(composer)
      await user.keyboard('/debug{Enter}')

      expect(screen.queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /预览当前草稿/ })).not.toBeInTheDocument()
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      expect(harnessService.previewBranchRequest).not.toHaveBeenCalled()
    })

    it('enables the Debug preview trigger only once the bound draft is previewable', async () => {
      // 测试意图：空草稿按「草稿为空」禁用；草稿可预览后才允许触发，
      // 禁用期间既不发 fresh GET 也不发预览 POST。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)

      expect(screen.queryByRole('button', { name: /预览当前草稿/ })).not.toBeInTheDocument()
      expect(latestControlRef.current?.previewDisabled).toBe(true)
      expect(latestControlRef.current?.previewDisabledReason).toBe('草稿为空')
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledTimes(1)
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()

      await user.click(composer)
      await user.type(composer, 'ready draft')
      await waitFor(() => expect(latestControlRef.current?.previewDisabled).toBe(false))
    })

    it('posts the live draft with the fresh thread cursor and keeps the draft on success', async () => {
      // 测试意图：预览先读 fresh 快照再用它重建游标，因此 POST 里的
      // expectedHeadEntryId/expectedNextCommandSequence 只能来自 fresh GET，
      // 而面板缓存仍停在旧游标；成功后草稿必须原样保留。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      vi.mocked(harnessService.previewProviderRequest).mockResolvedValueOnce(previewResponse({
        bodyJson: '{"messages":[{"role":"user","content":"preview test message"}]}',
        sourceHeadEntryId: 'head-2',
        notice: 'Draft preview snapshot',
      }))

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'preview test message')
      serveFresh(() => Promise.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' }))))
      invokeDraftPreview()

      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      const [threadId, request] = vi.mocked(harnessService.previewProviderRequest).mock.calls[0]!
      expect(threadId).toBe(THREAD_ID)
      // 既有 Thread 的预览是 owner-free 契约：只带 CAS 游标与命令，不携带 owner/target。
      expect(request).not.toHaveProperty('owner')
      expect(request).not.toHaveProperty('target')
      expect(request.expectedHeadEntryId).toBe('head-2')
      expect(request.expectedNextCommandSequence).toBe('7')
      expect(request.commands).toHaveLength(1)
      expect(request.commands[0]).toMatchObject({
        type: 'USER_MESSAGE',
        contents: [{ type: 'TEXT', text: 'preview test message' }],
      })

      // Debug 检查器展示回包
      expect(await screen.findByRole('heading', { level: 3, name: '请求预览' })).toBeInTheDocument()
      expect(screen.getByText('DRAFT_REQUEST_PREVIEW')).toBeInTheDocument()
      expect(screen.getByTestId('preview-request-body')).toHaveTextContent('preview test message')
      // 成功预览不清空草稿
      expect(composer).toHaveTextContent('preview test message')
    })

    it('blocks the preview POST and refreshes the cached snapshot when the fresh branch settings changed', async () => {
      // 测试意图：fresh 快照的 branch settings 与冻结的 effectiveBase 不一致时，
      // 本地明确报「会话设置已变化」并把 fresh 快照写回缓存，绝不发出预览 POST。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'settings moved')
      serveFresh(() => Promise.resolve(snapshot(thread({
        headEntryId: 'head-3',
        branchSettings: {
          agentName: 'coder',
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
          environmentName: null,
        },
      }))))
      invokeDraftPreview()

      await expectAlertText('会话设置已变化，请确认后重试')
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      await waitFor(() => expect(
        view.client.getQueryData(queryKeys.threads.snapshot(THREAD_ID))?.thread.headEntryId,
      ).toBe('head-3'))
      expect(composer).toHaveTextContent('settings moved')
    })

    it.each([
      [
        'a busy thread',
        () => snapshot(thread({ status: 'MODEL_STREAMING', processing: true })),
        '会话正在运行中，无法预览',
      ],
      [
        'queued commands',
        () => snapshot(thread(), { queuedCommands: [queuedCommand()] }),
        '队列中有未处理命令，无法预览',
      ],
      [
        'an active model invocation',
        () => snapshot(thread(), { modelInvocation: activeModelInvocation() }),
        '会话正在运行中，无法预览',
      ],
      [
        'active tool invocations',
        () => snapshot(thread(), { toolInvocations: [activeToolInvocation()] }),
        '会话正在运行中，无法预览',
      ],
    ])('blocks the preview POST when the fresh snapshot has %s', async (_case, serveSnapshot, message) => {
      // 测试意图：在途执行与排队只出现在 fresh 快照里，本地投影仍显示空闲；
      // 预览必须以 fresh 判定为准并给出明确原因，绝不发出 POST。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'fresh state moved on')
      serveFresh(serveSnapshot)
      invokeDraftPreview()

      await expectAlertText(message)
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      expect(composer).toHaveTextContent('fresh state moved on')
    })

    it('drops the fresh GET result when the draft changed while the snapshot was loading', async () => {
      // 测试意图：fresh GET 在途时草稿被继续编辑，回包不再可信：
      // 既不发 POST，也不把旧游标的快照投进检查器。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const gate = deferred<HarnessThreadSnapshotDTO>()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'initial text')
      serveFresh(() => gate.promise)
      await clickPreviewAndAwaitFreshGet(user)

      await user.type(composer, ' edited')
      await act(async () => {
        gate.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' })))
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      // 草稿已被继续编辑（插入点落在开头），两段文字都必须保留
      expect(composer).toHaveTextContent('initial text')
      expect(composer).toHaveTextContent('edited')
    })

    it('drops the fresh GET result when the target changed while the snapshot was loading', async () => {
      // 测试意图：fresh GET 在途时 /new 把目标切离绑定 Thread，
      // 迟到的快照不得再触发 POST 或渲染任何预览检查器。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const gate = deferred<HarnessThreadSnapshotDTO>()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'moving target')
      serveFresh(() => gate.promise)
      await clickPreviewAndAwaitFreshGet(user)

      await user.click(composer)
      await user.clear(composer)
      await user.keyboard('/new{Enter}')
      await act(async () => {
        gate.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' })))
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('drops the fresh GET result when the pane unmounted while the snapshot was loading', async () => {
      // 测试意图：fresh GET 在途时面板卸载，迟到的快照不得继续请求或更新已卸载的状态。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const gate = deferred<HarnessThreadSnapshotDTO>()
      const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'unmount me')
      serveFresh(() => gate.promise)
      await clickPreviewAndAwaitFreshGet(user)

      view.unmount()
      await act(async () => {
        gate.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' })))
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
    })

    it('shows the mapped 409 reason without echoing the raw detail and keeps the draft', async () => {
      // 测试意图：409 只按白名单 reason 呈现本地文案，绝不回显 detail；
      // 预览失败也不清空草稿，用户可以直接改后再试。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      vi.mocked(harnessService.previewProviderRequest).mockRejectedValueOnce(
        new ApiError('thread cursor moved', 409, 'CONFLICT', { reason: 'PREVIEW_STALE_CURSOR' }),
      )

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'draft to keep')
      invokeDraftPreview()

      await expectAlertText('会话游标已过期，请刷新状态后重试')
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)
      expect(screen.queryByText(/thread cursor moved/)).not.toBeInTheDocument()
      expect(composer).toHaveTextContent('draft to keep')
    })

    it('drops the preview response when the draft changed while the POST was in flight', async () => {
      // 测试意图：POST 在途时草稿被继续编辑，回包属于旧草稿：
      // 不得写进 Debug 检查器，也不得把新草稿的输入当成已预览。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'initial text')
      invokeDraftPreview()
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      await user.type(composer, ' edited')
      await act(async () => {
        gate.resolve(previewResponse({ bodyJson: '{"messages":[{"role":"user","content":"initial text"}]}' }))
        await gate.promise
      })

      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('heading', { level: 3, name: '请求预览' })).not.toBeInTheDocument()
      // 草稿已被继续编辑（插入点落在开头），两段文字都必须保留
      expect(composer).toHaveTextContent('initial text')
      expect(composer).toHaveTextContent('edited')
    })

    it('drops the preview response when the target changed while the POST was in flight', async () => {
      // 测试意图：POST 在途时 /new 把目标切离绑定 Thread，
      // 迟到的回包既不渲染检查器也不写入新目标的错误通道。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'moving target')
      invokeDraftPreview()
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      await user.click(composer)
      await user.clear(composer)
      await user.keyboard('/new{Enter}')
      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })

      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('drops the preview response when the pane unmounted while the POST was in flight', async () => {
      // 测试意图：POST 在途时面板卸载，迟到的回包既不能触发渲染也不能抛出未处理错误。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'unmount me')
      invokeDraftPreview()
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      view.unmount()
      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)
      // Debug 工具条的“返回会话”文案 `ai.runtime.debug.backToConversation` 需由 i18n 目录
      // 补齐（本切片不改 catalogs）；这里只排除该缺文案噪声，其它 console 错误仍必须为零。
      const otherErrors = consoleError.mock.calls.filter(([first]) =>
        typeof first !== 'string' || !first.startsWith('Missing i18n message:'))
      expect(otherErrors).toEqual([])
      consoleError.mockRestore()
    })

    it('accepts only one preview request for repeated clicks on the Debug title button', async () => {
      // 测试意图：请求期间通过 in-flight 栅栏挡住第二、三次预览。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'single flight')

      // 同一个 React batch 内重复触发，真正验证 ref 单飞栅栏。
      act(() => {
        void latestControlRef.current?.handlePreview()
        void latestControlRef.current?.handlePreview()
        void latestControlRef.current?.handlePreview()
      })
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      expect(latestControlRef.current?.previewDisabled).toBe(true)
      invokeDraftPreview()
      invokeDraftPreview()
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)

      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)
      expect(await screen.findByTestId('preview-request-body')).toBeInTheDocument()
    })

    it('previews a ready attachment through the Debug title button with the server upload handle', async () => {
      // 测试意图：草稿 pill 永远持有客户端 localId，POST 必须换成服务端 upload 句柄；
      // 预览成功既不提交，也不消费或释放草稿里的附件。
      const user = userEvent.setup()
      stubHashWorker()
      bindPaneTarget()
      mockDebugProjection()
      vi.mocked(harnessService.previewProviderRequest).mockResolvedValueOnce(previewResponse({
        bodyJson: '{"messages":[{"role":"user","content":"look at this"}]}',
      }))

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'look at this')
      const localId = await uploadAttachment(user, new File(['bytes'], 'shot.png', { type: 'image/png' }))
      expect(localId).toBeTruthy()
      expect(localId).not.toBe(SERVER_UPLOAD_ID)

      await waitFor(() => expect(latestControlRef.current?.previewDisabled).toBe(false))
      invokeDraftPreview()

      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      const contents = vi.mocked(harnessService.previewProviderRequest).mock.calls[0]![1]
        .commands.at(-1)?.contents
      expect(contents?.find((content) => content.type === 'ATTACHMENT'))
        .toMatchObject({ type: 'ATTACHMENT', uploadId: SERVER_UPLOAD_ID })
      expect(contents?.find((content) => content.type === 'TEXT'))
        .toMatchObject({ type: 'TEXT', text: 'look at this' })

      // 成功预览：不提交、不重新上传、不释放
      expect(harnessService.acceptThreadCommandBatch).not.toHaveBeenCalled()
      expect(fakeStorage.completeUpload).toHaveBeenCalledTimes(1)
      expect(fakeStorage.deleteUpload).not.toHaveBeenCalled()
      expect(await screen.findByTestId('preview-request-body')).toBeInTheDocument()
      // 草稿与 pill 都原样保留
      expect(composer).toHaveTextContent('look at this')
      expect(document.querySelector<HTMLElement>('[data-part-type="attachment"]')?.dataset.uploadId)
        .toBe(localId)
    })

    it('accepts only one preview request for repeated clicks with a ready attachment', async () => {
      // 测试意图：附件就绪后连点，in-flight 栅栏必须同时挡住重复预览与重复上传/释放。
      const user = userEvent.setup()
      stubHashWorker()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'single flight attachment')
      await uploadAttachment(user, new File(['bytes'], 'once.png', { type: 'image/png' }))

      // 尚未重新渲染的同一 batch 内连点，确保附件预览同样由 ref 单飞。
      act(() => {
        void latestControlRef.current?.handlePreview()
        void latestControlRef.current?.handlePreview()
        void latestControlRef.current?.handlePreview()
      })
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      expect(latestControlRef.current?.previewDisabled).toBe(true)
      invokeDraftPreview()
      invokeDraftPreview()
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)

      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })
      expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1)
      expect(fakeStorage.completeUpload).toHaveBeenCalledTimes(1)
      expect(fakeStorage.deleteUpload).not.toHaveBeenCalled()
      expect(composer).toHaveTextContent('single flight attachment')
    })

    it('drops the fresh GET result when the branch draft settings changed while the snapshot was loading', async () => {
      // 测试意图：消息草稿一个字都没动，只有 branch draft 的权限设置变了。
      // 过期判定必须同时比较 branch draft，否则会用旧设置去发预览。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const serveFresh = snapshotGate()
      const gate = deferred<HarnessThreadSnapshotDTO>()
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'settings moved')
      serveFresh(() => gate.promise)
      await clickPreviewAndAwaitFreshGet(user)

      await toggleYolo(user)
      expect(harnessService.setThreadYolo).toHaveBeenCalledTimes(1)
      await act(async () => {
        gate.resolve(snapshot(thread({ headEntryId: 'head-2', nextCommandSequence: '7' })))
        await gate.promise
      })

      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()
      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      // 消息草稿未变，说明拦截来自设置变化而不是输入变化
      expect(composer).toHaveTextContent('settings moved')
    })

    it('drops the preview response when the branch draft settings changed while the POST was in flight', async () => {
      // 测试意图：POST 在途时只改 branch draft 设置（消息草稿不变），
      // 迟到的回包属于旧设置组合，不得写进检查器或错误通道。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'settings moved')
      invokeDraftPreview()
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      await toggleYolo(user)
      expect(harnessService.setThreadYolo).toHaveBeenCalledTimes(1)
      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })

      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(composer).toHaveTextContent('settings moved')
    })

    it('does not display a stale 409 after the draft changes', async () => {
      // 测试意图：POST 在途时草稿变化，随后到达的 409 只属于旧请求，
      // 不能覆盖当前草稿的错误提示。
      const user = userEvent.setup()
      bindPaneTarget()
      mockDebugProjection()
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewProviderRequest)
        .mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await openDebugView(user)
      await user.click(composer)
      await user.type(composer, 'before')
      invokeDraftPreview()
      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))

      await user.type(composer, ' after')
      await act(async () => {
        gate.reject(new ApiError('obsolete conflict', 409, 'CONFLICT', { reason: 'PREVIEW_STALE_CURSOR' }))
        await gate.promise.catch(() => undefined)
      })

      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      // 草稿已被继续编辑（插入点落在开头），两段文字都必须保留
      expect(composer).toHaveTextContent('before')
      expect(composer).toHaveTextContent('after')
    })
  })

  describe('local branch draft Debug preview (session endpoint, no Thread creation)', () => {
    const BRANCH_START_ENTRY_ID = 'entry-turn-end'

    /** 会话里的 ROOT → TURN_END 前缀：分叉点是一个已关闭回合。 */
    function branchEntries(): HarnessSessionEntryDTO[] {
      return [
        {
          entryId: 'entry-root',
          sessionId: 'session-1',
          parentEntryId: null,
          entryType: 'ROOT',
          payloadJson: '{}',
          createTime: null,
        },
        {
          entryId: BRANCH_START_ENTRY_ID,
          sessionId: 'session-1',
          parentEntryId: 'entry-root',
          entryType: 'TURN_END',
          payloadJson: JSON.stringify({ outcome: 'COMPLETED' }),
          createTime: null,
        },
      ]
    }

    /** 本地分支草稿目标：会话与分叉点已定，Thread 尚未创建。 */
    function bindBranchDraftTarget() {
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({
          kind: 'NEW_THREAD_DRAFT',
          sessionId: 'session-1',
          startEntryId: BRANCH_START_ENTRY_ID,
          threadName: 'branch-1',
        }),
      )
    }

    /** 用真实的 /debug 斜杠命令进入本地分支草稿的 Debug 视图。 */
    async function openBranchDraftDebugView(user: ReturnType<typeof userEvent.setup>) {
      vi.mocked(harnessService.listSessionEntries).mockResolvedValue(branchEntries())
      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.keyboard('/debug{Enter}')
      await screen.findByRole('listbox', { name: '事件' })
      return composer
    }

    it('keeps local preview failure readable and permits a manual retry without writing commands', async () => {
      const user = userEvent.setup()
      bindBranchDraftTarget()
      vi.mocked(harnessService.previewBranchRequest)
        .mockRejectedValueOnce(new Error('Preview offline'))
        .mockResolvedValueOnce(previewResponse())
      const composer = await openBranchDraftDebugView(user)
      await user.click(composer)
      await user.type(composer, 'retry preview draft')
      invokeDraftPreview()
      await screen.findAllByText('请求预览失败')
      expect(screen.queryByText('Preview offline')).not.toBeInTheDocument()
      expect(composer).toHaveTextContent('retry preview draft')
      invokeDraftPreview()
      expect(await screen.findByText('DRAFT_REQUEST_PREVIEW')).toBeInTheDocument()
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
    })

    it('opens the branch draft Debug view, previews through the session endpoint and never creates a Thread', async () => {
      // 测试意图：本地分支草稿同样能进入 Debug 并触发真实预检；预检只走会话级
      // branch preview（startEntryId + commands），绝不为了预览创建 Thread，也不发
      // per-thread 预览（草稿没有可绑定的 threadId）。
      const user = userEvent.setup()
      bindBranchDraftTarget()
      vi.mocked(harnessService.previewBranchRequest).mockResolvedValue(previewResponse())
      const composer = await openBranchDraftDebugView(user)
      await user.click(composer)
      await user.type(composer, 'branch debug message')

      // Debug 覆盖整个 pane：控制区保持挂载但隐藏且惰性。
      const controlArea = document.querySelector<HTMLElement>('.thread-control-area')
      expect(controlArea).toHaveClass('debug-hidden')
      expect(controlArea).toHaveProperty('inert', true)

      expect(latestControlRef.current?.previewDisabled).toBe(false)
      invokeDraftPreview()

      await waitFor(() => expect(harnessService.previewBranchRequest).toHaveBeenCalledTimes(1))
      const [sessionId, request] = vi.mocked(harnessService.previewBranchRequest).mock.calls[0]!
      expect(sessionId).toBe('session-1')
      expect(request).not.toHaveProperty('owner')
      expect(request).not.toHaveProperty('target')
      expect(request.startEntryId).toBe(BRANCH_START_ENTRY_ID)
      expect(request.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
      expect(request.commands[0]?.contents)
        .toEqual([expect.objectContaining({ type: 'TEXT', text: 'branch debug message' })])

      // 预览是纯读取：不创建 Thread、不提交批次、不发 per-thread 预览。
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
      expect(harnessService.acceptThreadCommandBatch).not.toHaveBeenCalled()
      expect(harnessService.previewProviderRequest).not.toHaveBeenCalled()

      expect(await screen.findByText('DRAFT_REQUEST_PREVIEW')).toBeInTheDocument()
      expect(screen.getByTestId('preview-request-body')).toHaveTextContent('draft')
      // 成功预览不清空草稿，也不落地任何目标写入。
      expect(composer).toHaveTextContent('branch debug message')
    })

    it('returns from the branch draft Debug view with the draft, settings and target intact', async () => {
      // 测试意图：草稿侧 Debug 由同一工具条提供可见的返回入口；退出只切换视图，
      // 草稿、设置控件与 NEW_THREAD_DRAFT 绑定原地保留，且没有任何写请求。
      const user = userEvent.setup()
      bindBranchDraftTarget()
      const composer = await openBranchDraftDebugView(user)
      await user.click(composer)
      await user.type(composer, 'draft kept across branch debug')
      const permissionBefore = document.querySelector('[aria-label="权限模式"]')?.textContent

      await user.click(screen.getByRole('button', { name: '关闭 Debug', exact: true }))

      expect(document.querySelector('.thread-debug-back')).toBeNull()
      expect(screen.queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument()
      const conversation = await screen.findByLabelText('给 AI 发送消息')
      expect(conversation).toHaveTextContent('draft kept across branch debug')
      expect(screen.getByRole('button', { name: '权限模式' }).textContent).toBe(permissionBefore)
      expect(localStorage.getItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      )).toContain('NEW_THREAD_DRAFT')
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
      expect(harnessService.previewBranchRequest).not.toHaveBeenCalled()
    })

    it('drops the branch preview response when the draft settings changed while the POST was in flight', async () => {
      // 测试意图：消息草稿一个字都没动，只改了分支草稿设置（YOLO）；迟到的回包属于
      // 旧设置组合，迟到的预览不得写进检查器，也不得进入错误通道。
      const user = userEvent.setup()
      bindBranchDraftTarget()
      vi.mocked(harnessService.listSessionEntries).mockResolvedValue(branchEntries())
      const gate = deferred<ProviderRequestPreviewDTO>()
      vi.mocked(harnessService.previewBranchRequest).mockReturnValue(gate.promise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.keyboard('/debug{Enter}')
      await screen.findByRole('listbox', { name: '事件' })
      await user.click(composer)
      await user.type(composer, 'settings moved in branch draft')
      invokeDraftPreview()
      await waitFor(() => expect(harnessService.previewBranchRequest).toHaveBeenCalledTimes(1))

      await user.click(screen.getByRole('button', { name: '关闭 Debug', exact: true }))
      await user.click(screen.getByRole('button', { name: '权限模式' }))
      await user.click(await screen.findByRole('option', { name: 'YOLO' }))
      await waitFor(() =>
        expect(screen.getByRole('button', { name: '权限模式' })).toHaveTextContent('YOLO'))

      await act(async () => {
        gate.resolve(previewResponse())
        await gate.promise
      })

      expect(screen.queryByTestId('preview-request-body')).not.toBeInTheDocument()
      expect(screen.queryByRole('heading', { level: 3, name: '请求预览' })).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      // 草稿未被清除，说明拦截来自设置变化而不是输入变化。
      expect(composer).toHaveTextContent('settings moved in branch draft')
    })

    /** 目标可切换的根控制探针：绑定目标由同一个受控 state 提供，用于验证换绑时的身份隔离。 */
    function useSwitchablePaneControl(initialTarget: PaneTarget) {
      const [target, setTarget] = useState<PaneTarget>(initialTarget)
      const projection = useThreadProjection(isBoundTarget(target) ? target.threadId : '')
      return {
        setTarget,
        control: useRootThreadControl({
          owner: { type: 'CHAT', chatId: CHAT_ID },
          paneId: 'probe',
          target,
          setTarget,
          projection,
          agents,
          environments: [],
          defaults: {},
          focused: true,
        }),
      }
    }

    function renderSwitchablePane(initialTarget: PaneTarget) {
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      return renderHook(() => useSwitchablePaneControl(initialTarget), {
        wrapper: ({ children }) => (
          <QueryClientProvider client={client}>{children}</QueryClientProvider>
        ),
      })
    }

    it('clears the draft Debug view and preview inspector when the target is rebound', async () => {
      // 测试意图：草稿 A 的 Debug 模式、滚动与预览检查器都属于 A 的目标身份；预览成功后
      // 换绑到另一份草稿或新建 Session，必须整体回到会话视图（composer 可见），
      // 绝不残留 A 的预览，也不让不支持 Debug 的新建 Session 保持隐藏 composer。
      vi.mocked(harnessService.listSessionEntries).mockResolvedValue(branchEntries())
      const { result } = renderSwitchablePane({
        kind: 'NEW_THREAD_DRAFT',
        sessionId: 'session-1',
        startEntryId: BRANCH_START_ENTRY_ID,
        threadName: 'branch-1',
      })
      await waitFor(() => expect(result.current.control.activeDraft).not.toBeNull())

      act(() => result.current.control.boundViews.switchMode('debug'))
      act(() => result.current.control.boundViews.selectDebugInspector({
        type: 'preview',
        preview: previewResponse(),
      }))
      expect(result.current.control.boundViews.mode).toBe('debug')
      expect(result.current.control.boundViews.debugSelection).not.toBeNull()

      act(() => result.current.setTarget({ kind: 'NEW_SESSION_DRAFT' }))
      expect(result.current.control.target).toEqual({ kind: 'NEW_SESSION_DRAFT' })
      expect(result.current.control.boundViews.mode).toBe('conversation')
      expect(result.current.control.boundViews.debugSelection).toBeNull()
      expect(result.current.control.boundViews.mainView.debug).toBeUndefined()

      // 再回到 Debug 并切到同一 Session 的另一份分支草稿：身份不同，同样必须重置。
      act(() => result.current.control.boundViews.switchMode('debug'))
      act(() => result.current.control.boundViews.selectDebugInspector({
        type: 'preview',
        preview: previewResponse(),
      }))
      act(() => result.current.setTarget({
        kind: 'NEW_THREAD_DRAFT',
        sessionId: 'session-1',
        startEntryId: 'entry-other',
        threadName: 'branch-2',
      }))
      expect(result.current.control.boundViews.mode).toBe('conversation')
      expect(result.current.control.boundViews.debugSelection).toBeNull()
    })

    it('keeps the Debug view while the target identity is unchanged', async () => {
      // 测试意图：同身份的重复上报（例如分支草稿设置变化引起的重渲染）不能把 Debug
      // 视图和预览检查器清掉，否则刚生成的预览会被自己的重渲染抹掉。
      vi.mocked(harnessService.listSessionEntries).mockResolvedValue(branchEntries())
      const target = {
        kind: 'NEW_THREAD_DRAFT' as const,
        sessionId: 'session-1',
        startEntryId: BRANCH_START_ENTRY_ID,
        threadName: 'branch-1',
      }
      const { result } = renderSwitchablePane(target)
      await waitFor(() => expect(result.current.control.activeDraft).not.toBeNull())

      act(() => result.current.control.boundViews.switchMode('debug'))
      act(() => result.current.control.boundViews.selectDebugInspector({
        type: 'preview',
        preview: previewResponse(),
      }))
      act(() => result.current.setTarget({ ...target }))
      act(() => result.current.control.selectSession('session-1'))

      expect(result.current.control.boundViews.mode).toBe('debug')
      expect(result.current.control.boundViews.debugSelection).not.toBeNull()
    })
  })

  describe('owner-free bound thread interactions', () => {
    /** 无 owner 的面板：paneId 即默认绑定的 Thread，用于验证既有 Thread 不再需要 owner。 */
    function renderOwnerlessController(capabilities?: { readOnly?: boolean }) {
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      return renderHook(
        () =>
          useRootControlProbe({
            paneId: THREAD_ID,
            agents,
            environments: [],
            defaults: {},
            focused: false,
            capabilities,
          }),
        {
          wrapper: ({ children }) => (
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
          ),
        },
      )
    }

    it('defaults to a bound thread target without an owner and becomes fully interactive', async () => {
      // 测试意图：owner 未传时不访问 owner.type 也不崩溃，目标默认绑定 paneId 的 Thread；
      // 既有 Thread 的 owner-free 契约下，快照就绪后 composer、settings 与预览都开放，
      // 预览只被草稿就绪度限制，而不是被 owner 拒绝。
      const { result } = renderOwnerlessController()
      expect(result.current.target).toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })

      await waitFor(() => expect(result.current.activeDraft).not.toBeNull())
      expect(result.current.composer.disabled).toBe(false)
      expect(result.current.composer.settings).toBeDefined()
      expect(result.current.previewDisabled).toBe(true)
      expect(result.current.previewDisabledReason).toBe('草稿为空')
    })

    it('submits a message and a Goal batch without an owner through the thread command batch contract', async () => {
      // 测试意图：无 owner 的既有 Thread 仍能发送消息与 Goal；两者都走同一 per-thread
      // 命令批次，请求体只有 CAS 游标与命令，绝不伪造 owner/target，也不触碰创建入口。
      const { result } = renderOwnerlessController()
      await waitFor(() => expect(result.current.composer.disabled).toBe(false))

      await act(async () => {
        await result.current.composer.onSubmit([createTextPart('owner-free message')])
      })
      await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
      const [threadId, request] = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]!
      expect(threadId).toBe(THREAD_ID)
      expect(request).not.toHaveProperty('owner')
      expect(request).not.toHaveProperty('target')
      expect(request.expectedHeadEntryId).toBe('head-1')
      expect(request.expectedNextCommandSequence).toBe('1')
      expect(request.commands.map((command) => command.type)).toEqual(['USER_MESSAGE'])
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()

      act(() => result.current.submitGoal('owner-free goal'))
      await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2))
      const goalRequest = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[1]![1]
      expect(goalRequest.commands).toEqual([
        expect.objectContaining({ type: 'GOAL', text: 'owner-free goal' }),
      ])
    })

    it('keeps container navigation owner-scoped while thread writes no longer require an owner', async () => {
      // 测试意图：owner 只描述容器（Session / Thread 列表与创建）；既有 Thread 的
      // Agent 切换与重命名不再被 owner 拦截，而 Session/Thread 导航仍必须拒绝无 owner 面板。
      const { result } = renderOwnerlessController()
      await waitFor(() => expect(result.current.activeDraft).not.toBeNull())

      // 1. 切换 Agent 不再需要 owner
      act(() => result.current.selectAgent('assistant'))
      expect(result.current.error).toBeNull()

      // 2. 重命名不再需要 owner：打开面板后提交走真实 PUT
      act(() => {
        result.current.openRenameWithBackTo('thread', THREAD_ID, 'thread-name', null)
      })
      expect(result.current.renameTarget).not.toBeNull()
      await act(async () => {
        await result.current.submitRename('renamed without owner')
      })
      await waitFor(() => expect(harnessService.renameThread).toHaveBeenCalledWith(
        THREAD_ID,
        { name: 'renamed without owner' },
      ))

      // 3. 分支切换与 Session/Thread 导航仍属于 owner 容器范围
      act(() => {
        result.current.requestBranchFromEntry({
          entryId: 'e-1',
          sessionId: 's-1',
          parentEntryId: null,
          entryType: 'MESSAGE',
          payloadJson: '{}',
          createTime: null,
        })
      })
      expect(result.current.error).toBe('当前模式不支持分支切换或分叉')

      act(() => {
        result.current.selectSession({
          sessionId: 's-1',
          name: 'Session 1',
          createdAt: null,
          lastActivityAt: null,
          firstMessagePreview: null,
          threadCount: 1,
        })
      })
      expect(result.current.error).toBe('当前模式不支持分支切换或分叉')

      act(() => {
        result.current.selectThread({
          threadId: 't-2',
          parentThreadId: null,
          name: 'Thread 2',
          status: 'IDLE',
          processing: false,
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
          createdAt: null,
          updatedAt: null,
          headMessagePreview: null,
        })
      })
      expect(result.current.error).toBe('当前模式不支持分支切换或分叉')
      expect(result.current.target.kind).toBe('BOUND_THREAD')
    })

    it('allows owner-free Goal and YOLO on a bound thread while container creation stays owner-scoped', async () => {
      // 测试意图：本地界面命令（debug / shortcuts / goal / yolo）在无 owner 的既有 Thread
      // 上仍然可用，因为它们是 owner-free 的执行控制；只有创建新容器（new）需要 owner。
      const { result } = renderOwnerlessController()
      await waitFor(() => expect(result.current.activeDraft).not.toBeNull())
      act(() => result.current.dismissActionError())

      // debug 与 shortcuts 正常可用
      expect(result.current.boundViews.mode).toBe('conversation')
      act(() => {
        result.current.composer.onCommand(testCommand('debug'))
      })
      expect(result.current.boundViews.mode).toBe('debug')
      act(() => {
        result.current.composer.onCommand(testCommand('shortcuts'))
      })
      expect(result.current.interaction).toBe('shortcuts')

      // Goal 面板 owner-free 打开
      act(() => {
        result.current.closeInteraction()
      })
      act(() => {
        result.current.composer.onCommand(testCommand('goal'))
      })
      expect(result.current.interaction).toBe('goal')
      act(() => {
        result.current.closeInteraction()
      })

      // YOLO 是 owner-free 的 per-thread 策略面，直接提交目标策略
      act(() => {
        result.current.composer.onCommand(testCommand('yolo'))
      })
      await waitFor(() => expect(harnessService.setThreadYolo).toHaveBeenCalledWith(
        THREAD_ID,
        { yoloEnabled: true },
      ))

      // new 仍被拦截：无 owner 面板没有可导航的容器
      act(() => {
        result.current.composer.onCommand(testCommand('new'))
      })
      expect(result.current.target).toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    })

    it('renders AgentPane without an owner and renames the bound thread from the palette', async () => {
      // 测试意图：AgentPane 完全不传 owner 时不得崩溃，Thread 重命名不再按 owner 拦截，
      // 且真实提交后能落 PUT。入口由 /rename-thread 提供（面板不再自渲染身份标题）。
      const user = userEvent.setup()
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      render(
        <QueryClientProvider client={client}>
          <AgentPane
            paneId={THREAD_ID}
            agents={agents}
            environments={[]}
            focused
          />
        </QueryClientProvider>,
      )

      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.keyboard('/rename-thread{Enter}')
      const input = await screen.findByRole('textbox', { name: '名称' })
      expect(input).toHaveValue('thread-name')
      await user.clear(input)
      await user.type(input, 'renamed without owner')
      await user.click(screen.getByRole('button', { name: '保存' }))

      await waitFor(() => expect(harnessService.renameThread).toHaveBeenCalledWith(
        THREAD_ID,
        { name: 'renamed without owner' },
      ))
    })

    it('applies the same owner-free bound interaction for CHAT, ISSUE_AGENT and absent owners', async () => {
      // 测试意图：矩阵级验证——既有 Thread 的能力不再由 owner 类型裁剪：三种 owner 状态下
      // 预览都只受草稿就绪度限制、settings 都暴露，owner 概念只保留给容器创建。
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

      // 1. CHAT owner
      const { result: chatResult } = renderHook(
        () =>
          useRootControlProbe({
            owner: { type: 'CHAT', chatId: CHAT_ID },
            paneId: 'p-chat',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
      )
      await waitFor(() => expect(chatResult.current.activeDraft).not.toBeNull())
      expect(chatResult.current.previewDisabledReason).toBe('草稿为空')
      expect(chatResult.current.composer.settings).toBeDefined()

      // 2. ISSUE_AGENT owner：与 CHAT 完全相同的既有 Thread 行为
      const { result: issueResult } = renderHook(
        () =>
          useRootControlProbe({
            owner: { type: 'ISSUE_AGENT', issueId: 'issue-99', agentName: 'coder' },
            paneId: 'p-issue',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
      )
      await waitFor(() => expect(issueResult.current.activeDraft).not.toBeNull())
      expect(issueResult.current.previewDisabledReason).toBe('草稿为空')
      expect(issueResult.current.composer.settings).toBeDefined()

      // 3. 无 owner：同样开放预览与 settings，只有容器导航仍被拒绝
      const { result: nullResult } = renderHook(
        () =>
          useRootControlProbe({
            paneId: 'p-null',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
      )
      await waitFor(() => expect(nullResult.current.activeDraft).not.toBeNull())
      expect(nullResult.current.previewDisabledReason).toBe('草稿为空')
      expect(nullResult.current.composer.settings).toBeDefined()
      act(() => nullResult.current.composer.onCommand(testCommand('new')))
      expect(nullResult.current.target).toEqual({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    })
  })

  describe('Bound thread replay persistence and real UI controls', () => {
    it('renders retry/abandon controls on unknown post failure and retries exact command batch via real button click', async () => {
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      vi.mocked(harnessService.acceptThreadCommandBatch)
        .mockRejectedValueOnce(new Error('Network disconnected'))
        .mockResolvedValueOnce(acceptedResponse())

      renderPane({ type: 'CHAT', chatId: CHAT_ID })

      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.type(composer, 'Hello bound unknown')
      const sendButton = screen.getByRole('button', { name: '发送消息' })
      await user.click(sendButton)

      // 验证初次发送：绑定 Thread 走 owner-free 的 per-thread 命令批次
      await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
      const [firstThreadId, firstBatch] = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]!
      expect(firstThreadId).toBe(THREAD_ID)
      expect(firstBatch).toBeDefined()
      const firstKey = firstBatch?.commands[0]?.idempotencyKey
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()

      // 界面渲染出真实的 bound-pending-controls
      const controls = await screen.findByTestId('bound-pending-controls')
      expect(controls).toBeInTheDocument()

      // 输入框被禁用（aria-disabled="true" 且 contenteditable="false"），防止在 unknown 状态下修改身份
      expect(composer).toHaveAttribute('aria-disabled', 'true')
      expect(composer).toHaveAttribute('contenteditable', 'false')

      // 验证 localStorage 存在冻结的 pending 消息
      const pendingKey = `kk-studio.agent-thread-pending.${THREAD_ID}`
      expect(localStorage.getItem(pendingKey)).not.toBeNull()

      // 点击真实的“重试”按钮（在途提交收尾前该控件保持禁用，等它解锁再点）
      const retryButton = screen.getByRole('button', { name: '重试' })
      // 在途提交收尾前控件保持禁用；先等它解锁，再点击真实按钮。
      await waitFor(() => expect(retryButton).toBeEnabled())
      await user.click(retryButton)

      // 验证重试发送了完全相同的 batch（逐字节复用相同 idempotencyKey）
      await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2))
      const secondBatch = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[1]?.[1]
      expect(secondBatch?.commands[0]?.idempotencyKey).toBe(firstKey)

      // 成功后，controls 消失，localStorage 被清空，输入框解锁
      await waitFor(() => expect(screen.queryByTestId('bound-pending-controls')).toBeNull())
      expect(localStorage.getItem(pendingKey)).toBeNull()
      expect(composer).toHaveAttribute('aria-disabled', 'false')
    })

    it('cleans up local storage pending message and unlocks composer on abandon click', async () => {
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      vi.mocked(harnessService.acceptThreadCommandBatch)
        .mockRejectedValueOnce(new Error('Gateway timeout'))

      renderPane({ type: 'CHAT', chatId: CHAT_ID })

      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.type(composer, 'Abandon me')
      await user.click(screen.getByRole('button', { name: '发送消息' }))

      await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
      await screen.findByTestId('bound-pending-controls')
      const pendingKey = `kk-studio.agent-thread-pending.${THREAD_ID}`
      expect(localStorage.getItem(pendingKey)).not.toBeNull()
      expect(composer).toHaveAttribute('aria-disabled', 'true')

      // 点击真实的“取消/放弃”按钮（在途提交收尾前该控件保持禁用，等它解锁再点）
      const abandonButton = screen.getByRole('button', { name: '取消' })
      await waitFor(() => expect(abandonButton).toBeEnabled())
      await user.click(abandonButton)

      // 验证控制栏消失，本地 pending 清理，输入框解锁，且未发起新的网络请求
      await waitFor(() => expect(screen.queryByTestId('bound-pending-controls')).toBeNull())
      expect(localStorage.getItem(pendingKey)).toBeNull()
      expect(composer).toHaveAttribute('aria-disabled', 'false')
      expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)
    })

    it('aborts sending before POST when localStorage persistence fails (fail-closed)', async () => {
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      const originalSetItem = localStorage.setItem.bind(localStorage)
      const storageSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation((key, val) => {
        if (key.includes('agent-thread-pending')) {
          throw new Error('QuotaExceeded')
        }
        return originalSetItem(key, val)
      })

      try {
        renderPane({ type: 'CHAT', chatId: CHAT_ID })

        const composer = await screen.findByLabelText('给 AI 发送消息')
        await user.click(composer)
        await user.type(composer, 'Fail closed attempt')
        await user.click(screen.getByRole('button', { name: '发送消息' }))

        // 验证 fail-closed：未调用 acceptThreadCommandBatch
        expect(harnessService.acceptThreadCommandBatch).not.toHaveBeenCalled()
        // 出现明确的存储失败可重试错误提示
        await waitFor(() => {
          expect(screen.getByText(/无法保存发送记录/)).toBeInTheDocument()
        })
      } finally {
        storageSpy.mockRestore()
      }
    })

    it('restores unknown pending message from localStorage on reload and allows retry via real button', async () => {
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      const pendingKey = `kk-studio.agent-thread-pending.${THREAD_ID}`
      // 模拟 POST 刚发出后页面被立即刷新（unknownOutcome 初始存为 false）
      localStorage.setItem(
        pendingKey,
        JSON.stringify({
          threadId: THREAD_ID,
          unknownOutcome: false,
          targetDraft: {
            agentName: 'assistant',
            model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
            environmentName: null,
            yoloEnabled: false,
          },
          kind: 'MESSAGE',
          localDraft: [{ type: 'text', partId: 'p-reload', text: 'reload test message' }],
          goalText: null,
          // 既有 Thread 的冻结请求：只有精确 CAS 游标与命令，没有 owner/target。
          request: {
            expectedHeadEntryId: 'h1',
            expectedNextCommandSequence: '1',
            commands: [
              {
                type: 'USER_MESSAGE',
                idempotencyKey: 'cmd-reloaded-key-1',
                contents: [{ type: 'TEXT', text: 'reload test message' }],
              },
            ],
          },
        }),
      )
      vi.mocked(harnessService.acceptThreadCommandBatch).mockResolvedValueOnce(acceptedResponse())

      renderPane({ type: 'CHAT', chatId: CHAT_ID })

      // 验证刷新挂载后自动恢复 unknownOutcome = true，并展示 controls
      const controls = await screen.findByTestId('bound-pending-controls')
      expect(controls).toBeInTheDocument()

      const composer = await screen.findByLabelText('给 AI 发送消息')
      expect(composer).toHaveAttribute('aria-disabled', 'true')

      // 点击真实的“重试”按钮
      const retryButton = screen.getByRole('button', { name: '重试' })
      await user.click(retryButton)

      // 验证使用原请求 command idempotencyKey 重发，且按 threadId 走 per-thread 入口
      await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
      const [reloadedThreadId, batch] = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]!
      expect(reloadedThreadId).toBe(THREAD_ID)
      expect(batch).not.toHaveProperty('owner')
      expect(batch).not.toHaveProperty('target')
      expect(batch?.commands[0]?.idempotencyKey).toBe('cmd-reloaded-key-1')
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()

      // 重试成功后状态清理
      await waitFor(() => expect(screen.queryByTestId('bound-pending-controls')).toBeNull())
      expect(localStorage.getItem(pendingKey)).toBeNull()
      expect(composer).toHaveAttribute('aria-disabled', 'false')
    })

    it('removes retry/abandon controls while a retry is in flight and accepts only one retry request', async () => {
      // 测试意图：unknown 重试会同步把 unknownOutcome 置为 false，控件随之从 DOM 移除；
      // 连点因此无法再次触发重试。同步 in-flight ref 另由 thread controller 单测覆盖。
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      let resolveRetry!: (val: HarnessThreadCommandDTO[]) => void
      const retryPromise = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
        resolveRetry = resolve
      })

      vi.mocked(harnessService.acceptThreadCommandBatch)
        .mockRejectedValueOnce(new Error('Network disconnected'))
        .mockReturnValueOnce(retryPromise)

      renderPane({ type: 'CHAT', chatId: CHAT_ID })

      const composer = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer)
      await user.type(composer, 'Hello rapid click test')
      await user.click(screen.getByRole('button', { name: '发送消息' }))

      await screen.findByTestId('bound-pending-controls')
      const retryBtn = screen.getByRole('button', { name: '重试' })
      const cancelBtn = screen.getByRole('button', { name: '取消' })
      // 在途提交收尾前两个控件都禁用；解锁后才允许重试，因此这里先等解锁再断言与点击。
      await waitFor(() => expect(retryBtn).toBeEnabled())
      expect(cancelBtn).toBeEnabled()

      await user.click(retryBtn)
      await waitFor(() => expect(screen.queryByTestId('bound-pending-controls')).toBeNull())
      expect(screen.queryByRole('button', { name: '重试' })).toBeNull()
      expect(screen.queryByRole('button', { name: '取消' })).toBeNull()
      await user.click(composer)
      expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2)

      resolveRetry([])
      await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2))
      expect(screen.queryByTestId('bound-pending-controls')).toBeNull()
    })

    it('keeps an externally updated draft after an in-flight submission succeeds', async () => {
      // 测试意图：绑定 composer 在 pending 期间对用户输入禁用，不能靠 user.type 改草稿。
      // 通过真实 onPartsChange 模拟飞行中的外部草稿更新，成功返回后不得被清空。
      let resolveFirst!: (val: HarnessThreadCommandDTO[]) => void
      const firstCallPromise = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
        resolveFirst = resolve
      })
      vi.mocked(harnessService.acceptThreadCommandBatch).mockReturnValueOnce(firstCallPromise)

      const client = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      const { result } = renderHook(
        () =>
          useRootControlProbe({
            owner: { type: 'CHAT', chatId: CHAT_ID },
            paneId: 'pane-draft',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: true,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
      )
      await waitFor(() => expect(result.current.composer.disabled).toBe(false))

      act(() => result.current.composer.onPartsChange([createTextPart('first message')]))
      let submission!: Promise<void>
      act(() => {
        submission = result.current.composer.onSubmit()
      })
      await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
      expect(result.current.composer.disabled).toBe(true)

      act(() => result.current.composer.onPartsChange([createTextPart('typing next message while in flight')]))
      expect(result.current.composer.parts).toEqual([
        expect.objectContaining({ type: 'text', text: 'typing next message while in flight' }),
      ])

      await act(async () => {
        resolveFirst([])
        await submission
      })
      await waitFor(() => expect(result.current.composer.pending).toBe(false))
      expect(result.current.composer.parts).toEqual([
        expect.objectContaining({ type: 'text', text: 'typing next message while in flight' }),
      ])
    })

    it('rejects interleaved pane from overwriting first pane pending and protects first pending on stop', async () => {
      // 测试意图：第二 pane 先挂载并持有自己的 request，第一 pane 随后写入不同身份。
      // storage 必须拒绝覆盖；第二 pane 的 stop 因身份不匹配不得清理第一 pane 的 pending。
      const user = userEvent.setup()
      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      let resolvePane2!: (val: HarnessThreadCommandDTO[]) => void
      const pane2Request = new Promise<HarnessThreadCommandDTO[]>((resolve) => {
        resolvePane2 = resolve
      })
      vi.mocked(harnessService.acceptThreadCommandBatch)
        .mockReturnValueOnce(pane2Request)
        .mockRejectedValueOnce(new Error('Network error on pane 1'))
      vi.mocked(harnessService.stopThread).mockResolvedValueOnce({
        status: 'STOPPED',
        thread: thread({ executionControl: 'STOPPED' }),
        stoppedThreads: [],
      })

      const client2 = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      })
      const { result: controller2 } = renderHook(
        () =>
          useRootControlProbe({
            owner: { type: 'CHAT', chatId: CHAT_ID },
            paneId: 'pane-2',
            initialTarget: { kind: 'BOUND_THREAD', threadId: THREAD_ID },
            agents,
            environments: [],
            defaults: {},
            focused: false,
          }),
        { wrapper: ({ children }) => <QueryClientProvider client={client2}>{children}</QueryClientProvider> },
      )
      await waitFor(() => expect(controller2.current.composer.disabled).toBe(false))
      expect(controller2.current.pendingMessage).toBeNull()

      act(() => controller2.current.composer.onPartsChange([createTextPart('pane 2 message')]))
      act(() => {
        void controller2.current.composer.onSubmit()
      })
      await waitFor(() => expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1))
      const pendingKey = `kk-studio.agent-thread-pending.${THREAD_ID}`
      const pane2Stored = localStorage.getItem(pendingKey)
      expect(pane2Stored).not.toBeNull()
      expect(controller2.current.pendingMessage?.request).toBeDefined()

      await act(async () => {
        resolvePane2([])
      })
      await waitFor(() => expect(controller2.current.pendingMessage).toBeNull())
      expect(localStorage.getItem(pendingKey)).toBeNull()

      const pane1 = renderPane({ type: 'CHAT', chatId: CHAT_ID })
      const composer1 = await screen.findByLabelText('给 AI 发送消息')
      await user.click(composer1)
      await user.type(composer1, 'pane 1 message')
      await user.click(screen.getByRole('button', { name: '发送消息' }))
      await screen.findByTestId('bound-pending-controls')

      const pane1Stored = localStorage.getItem(pendingKey)
      expect(pane1Stored).not.toBeNull()
      expect(pane1Stored).not.toBe(pane2Stored)
      expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2)

      act(() => controller2.current.composer.onPartsChange([createTextPart('pane 2 retry')]))
      await act(async () => {
        await controller2.current.composer.onSubmit()
      })
      expect(controller2.current.error).toMatch(/无法保存发送记录/)
      expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(2)
      expect(localStorage.getItem(pendingKey)).toBe(pane1Stored)
      expect(controller2.current.pendingMessage).toBeNull()

      await act(async () => {
        const stopped = controller2.current.controller.stopThread()
        await Promise.resolve()
        await stopped
      })
      expect(harnessService.stopThread).toHaveBeenCalledTimes(1)
      expect(localStorage.getItem(pendingKey)).toBe(pane1Stored)
      expect(controller2.current.pendingMessage).toBeNull()

      pane1.unmount()
    })
  })

  describe('selectAgent model follow & validation behavior', () => {
    const claudeModel = {
      providerName: 'anthropic',
      name: 'Claude',
      description: null,
      config: {
        limit: { context: 200000, output: 4096 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
          currency: 'USD',
          pricingTier: 'default',
          serviceTier: 'standard',
          serviceTierMultiplier: 1,
        },
        defaultVariant: 'v1',
        variants: [{ id: 'v1' }, { id: 'fast' }],
      },
      version: '0',
      createTime: null,
      updateTime: null,
    }

    const coderAgent: AgentDefinitionDTO = {
      name: 'coder',
      description: null,
      systemPrompt: null,
      model: 'anthropic/Claude',
      variant: 'fast',
      environmentId: null,
      config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
      version: '0',
      createTime: null,
      updateTime: null,
    }

    const brokenAgent: AgentDefinitionDTO = {
      name: 'broken-agent',
      description: null,
      systemPrompt: null,
      model: 'nonexistent/model',
      variant: null,
      environmentId: null,
      config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
      version: '0',
      createTime: null,
      updateTime: null,
    }

    // 两类入口均需先验证完整预设；拒绝选择不得清空正文、附件或关闭选择面板。
    it.each(['blank', 'bound'])('preserves draft and interaction on invalid agent selection in %s mode', async (mode) => {
      vi.mocked(agentService.listAgents).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 3,
        results: [agents[0]!, coderAgent, brokenAgent],
      })
      vi.mocked(agentService.listModels).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [models[0]!, claudeModel],
      })

      if (mode === 'bound') {
        localStorage.setItem(
          `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
          JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
        )
      }
      const hook = renderController({ agents: [agents[0]!, coderAgent, brokenAgent] })
      await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())
      const initialDraft = { ...hook.result.current.activeDraft! }

      act(() => {
        hook.result.current.composer.onPartsChange([
          createTextPart('preserved draft text'),
          { type: 'attachment', partId: 'attachment-1', uploadId: 'local-upload-1', filename: 'notes.txt' },
        ])
        hook.result.current.openInteraction('agent')
      })
      const preservedParts = hook.result.current.composer.parts
      expect(hook.result.current.interaction).toBe('agent')

      // 选择无法解析模型的 agent
      act(() => {
        hook.result.current.selectAgent('broken-agent')
      })

      // 验证：报错、保留 interaction、保留原 draft，绝不将 localDraft 置 null
      expect(hook.result.current.error).toBe('Agent broken-agent 的模型或变体无法解析，请选择其他 Agent。')
      expect(hook.result.current.interaction).toBe('agent')
      expect(hook.result.current.activeDraft).toEqual(initialDraft)
      expect(partsToText(hook.result.current.composer.parts)).toBe('preserved draft text')
      expect(hook.result.current.composer.parts).toEqual(preservedParts)

      // 成功选择 coder agent：关闭交互并原子更新 agentName 和 model
      act(() => {
        hook.result.current.selectAgent('coder')
      })
      expect(hook.result.current.error).toBeNull()
      expect(hook.result.current.interaction).toBeNull()
      expect(hook.result.current.activeDraft?.agentName).toBe('coder')
      expect(hook.result.current.activeDraft?.model).toEqual({
        providerName: 'anthropic',
        modelName: 'Claude',
        variant: 'fast',
      })
      expect(partsToText(hook.result.current.composer.parts)).toBe('preserved draft text')
      expect(hook.result.current.composer.parts).toEqual(preservedParts)

      // 手动修改模型后，再次选择同一 agent 显式重置为该 agent 的预设模型
      act(() => {
        hook.result.current.composer.settings?.onModelChange({
          providerName: 'custom',
          modelName: 'Custom',
          variant: 'v1',
        })
      })
      expect(hook.result.current.activeDraft?.model.modelName).toBe('Custom')

      act(() => {
        hook.result.current.selectAgent('coder')
      })
      expect(hook.result.current.activeDraft?.model).toEqual({
        providerName: 'anthropic',
        modelName: 'Claude',
        variant: 'fast',
      })
    })

    it('atomically follows target agent model in bound mode, sending SET_AGENT+SET_MODEL+USER_MESSAGE on submit', async () => {
      // 发送必须把 Agent 与模型设置放在同一消息批次，不能只更新 UI 标签。
      vi.mocked(agentService.listAgents).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [agents[0]!, coderAgent],
      })
      vi.mocked(agentService.listModels).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [models[0]!, claudeModel],
      })

      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      const hook = renderController({ agents: [agents[0]!, coderAgent] })
      await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())

      act(() => {
        hook.result.current.selectAgent('coder')
      })
      expect(hook.result.current.activeDraft?.agentName).toBe('coder')
      expect(hook.result.current.activeDraft?.model).toEqual({
        providerName: 'anthropic',
        modelName: 'Claude',
        variant: 'fast',
      })

      await act(async () => {
        await hook.result.current.composer.onSubmit([createTextPart('bound message')])
      })

      expect(harnessService.acceptThreadCommandBatch).toHaveBeenCalledTimes(1)
      const [boundThreadId, batchRequest] = vi.mocked(harnessService.acceptThreadCommandBatch).mock.calls[0]!
      expect(boundThreadId).toBe(THREAD_ID)
      // owner-free：请求体只有 CAS 游标与命令
      expect(batchRequest).not.toHaveProperty('owner')
      expect(batchRequest).not.toHaveProperty('target')
      expect(batchRequest.expectedHeadEntryId).toBe('head-1')
      expect(batchRequest.expectedNextCommandSequence).toBe('1')
      expect(batchRequest.commands.map((c) => c.type)).toEqual([
        'SET_AGENT',
        'SET_MODEL',
        'USER_MESSAGE',
      ])
      expect(batchRequest.commands[0]).toMatchObject({ agentName: 'coder' })
      expect(batchRequest.commands[1]).toMatchObject({
        model: { providerName: 'anthropic', modelName: 'Claude', variant: 'fast' },
      })
      expect(harnessService.acceptCommandBatch).not.toHaveBeenCalled()
    })

    it('includes SET_AGENT and SET_MODEL for the new agent model in the bound preview request batch', async () => {
      // 预览与发送使用同一草稿，预览不得遗留旧模型。
      vi.mocked(agentService.listAgents).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [agents[0]!, coderAgent],
      })
      vi.mocked(agentService.listModels).mockResolvedValue({
        pageNumber: 1,
        pageSize: 50,
        totalCount: 2,
        results: [models[0]!, claudeModel],
      })

      localStorage.setItem(
        `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:probe`,
        JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
      )
      mockDebugProjection()
      const serveFresh = snapshotGate()
      vi.mocked(harnessService.previewProviderRequest).mockResolvedValueOnce(previewResponse({
        providerName: 'anthropic',
        modelName: 'Claude',
        bodyJson: '{"messages":[{"role":"user","content":"preview test message"}]}',
        sourceHeadEntryId: 'head-1',
      }))

      const hook = renderController({ agents: [agents[0]!, coderAgent] })
      await waitFor(() => expect(hook.result.current.activeDraft).not.toBeNull())

      // 切换到 coder agent
      act(() => {
        hook.result.current.selectAgent('coder')
        hook.result.current.composer.onPartsChange([createTextPart('preview test message')])
        hook.result.current.composer.onPreviewReadinessChange?.({ canPreview: true, reason: null })
      })
      expect(hook.result.current.activeDraft?.agentName).toBe('coder')
      expect(hook.result.current.activeDraft?.model.modelName).toBe('Claude')

      // 挂载 composerRef handle
      hook.result.current.composer.composerRef.current = {
        preparePreview: () => ({
          payload: [createTextPart('preview test message')],
          localDraft: [createTextPart('preview test message')],
        }),
      }

      serveFresh(() => Promise.resolve(snapshot(thread({ headEntryId: 'head-1', nextCommandSequence: '1' }))))

      await act(async () => {
        await hook.result.current.handlePreview()
      })

      await waitFor(() => expect(harnessService.previewProviderRequest).toHaveBeenCalledTimes(1))
      const [threadId, request] = vi.mocked(harnessService.previewProviderRequest).mock.calls[0]!
      expect(threadId).toBe(THREAD_ID)
      expect(request.commands.map((c) => c.type)).toEqual([
        'SET_AGENT',
        'SET_MODEL',
        'USER_MESSAGE',
      ])
      expect(request.commands[0]).toMatchObject({ agentName: 'coder' })
      expect(request.commands[1]).toMatchObject({
        model: { providerName: 'anthropic', modelName: 'Claude', variant: 'fast' },
      })
    })
  })
})

describe('AgentPane root control and child observation', () => {
  const CHILD_THREAD_ID = '00000000-0000-4000-8000-0000000000c1'

  function bindPaneToRoot() {
    localStorage.setItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
  }

  function snapshotForThread(threadId: string) {
    const isRoot = threadId === THREAD_ID
    return snapshot(thread({
      threadId,
      name: isRoot ? 'root thread' : 'worker',
      parentThreadId: isRoot ? null : THREAD_ID,
      status: 'IDLE',
      processing: false,
    }))
  }

  it('navigates to the interaction source in the same pane and keeps the hidden root mounted', async () => {
    // 根面板聚合后代审批：来源标识与代理名来自执行树；点击来源在当前 pane 查看子代理，
    // 根层隐藏为 inert 但保持挂载，草稿随返回原地保留。
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(THREAD_ID, null, 'root thread', false),
      treeNode(CHILD_THREAD_ID, THREAD_ID, 'worker', false),
    ])
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(
      async (threadId: string) => snapshotForThread(threadId),
    )
    vi.mocked(interactionService.listInteractions).mockResolvedValue({
      items: [approvalInteraction(CHILD_THREAD_ID)],
      nextCursor: null,
    })
    bindPaneToRoot()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })

    const source = await screen.findByRole('link', { name: /worker/ })
    expect(source).toHaveAttribute('href', `/threads/${CHILD_THREAD_ID}`)
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'root draft kept')
    const unmountsBeforeObservation = controlMounts.unmount.mock.calls.length
    const targetBeforeObservation = localStorage.getItem(
      `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`,
    )

    await user.click(source)

    const back = await screen.findByRole('button', { name: '回到父 agent' })
    expect(screen.getByText('只读查看')).toBeInTheDocument()
    // 返回入口在查看层顶部标题区（名称/只读标识旁），不是 transcript 与 widget 之后的底部控制区。
    expect(back.closest('.agent-pane-thread-heading')).not.toBeNull()
    const paneSection = back.closest('.chat-pane') as HTMLElement
    const childLayer = paneSection.querySelector('.chat-pane-layer:not([hidden])') as HTMLElement
    const childTranscript = childLayer.querySelector('[role="log"]') as HTMLElement
    // 标题区是主列的首个元素（顶部非滚动区），transcript 在其之后。
    expect(paneSection.firstElementChild)
      .toBe(back.closest('.agent-pane-thread-heading'))
    expect(
      back.compareDocumentPosition(childTranscript) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    expect(harnessService.getThreadTree).toHaveBeenCalledWith(THREAD_ID)
    expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(CHILD_THREAD_ID)

    const layers = document.querySelectorAll('.chat-pane-layer')
    expect(layers).toHaveLength(2)
    expect(layers[0]).toHaveAttribute('hidden')
    expect(layers[0]).toHaveAttribute('inert')
    // 意图 B（只观察）：pane 绑定目标不变、根控制面不卸载，只是同一 pane 内多了一层。
    expect(controlMounts.unmount).toHaveBeenCalledTimes(unmountsBeforeObservation)
    expect(localStorage.getItem(`kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`))
      .toBe(targetBeforeObservation)
    // 隐藏的根层仍是同一个挂载实例：草稿没有被重建或丢弃。
    expect(composer.isConnected).toBe(true)
    // 隐藏的根层不持有焦点（也不被其他层抢走）：停止加载后焦点不在隐藏子树内。
    expect(document.activeElement).toBe(document.body)

    await user.click(back)

    expect(layers[0]).not.toHaveAttribute('hidden')
    expect(composer).toHaveTextContent('root draft kept')
    // 返回后焦点落在重新激活的根层内（根 Composer 激活时会主动恢复自身焦点，
    // 优先级高于触发点恢复），不停留在隐藏层，也不丢到 body。
    expect(layers[0].contains(document.activeElement)).toBe(true)
    expect(document.activeElement).not.toBe(document.body)
  })

  it('keeps the covered root composer inert, unfocusable and out of global shortcuts', async () => {
    // 根面板查看期间：隐藏层的 Composer 不激活（hidden/aria-hidden），全局 Escape
    // 不会再聚焦它，也不会有输入被送往隐藏的根。
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(THREAD_ID, null, 'root thread', false),
      treeNode(CHILD_THREAD_ID, THREAD_ID, 'worker', false),
    ])
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(
      async (threadId: string) => snapshotForThread(threadId),
    )
    vi.mocked(interactionService.listInteractions).mockResolvedValue({
      items: [approvalInteraction(CHILD_THREAD_ID)],
      nextCursor: null,
    })
    bindPaneToRoot()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })

    const source = await screen.findByRole('link', { name: /worker/ })
    await screen.findByLabelText('给 AI 发送消息')
    await user.click(source)

    const composerRegion = document.querySelector('.thread-composer')
    expect(composerRegion).toHaveAttribute('hidden')
    expect(composerRegion).toHaveAttribute('aria-hidden', 'true')

    // 隐藏的根 Composer 不再持有焦点，全局 Escape 也不会把焦点带回隐藏层。
    expect(composerRegion?.contains(document.activeElement)).toBe(false)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(composerRegion?.contains(document.activeElement)).toBe(false)
  })

  it('submits the runtime root Stop command with the root id while only descendants process', async () => {
    // 根本地空闲但后代仍在处理：Stop 仍然可用，并且请求必须落在执行根（runtime root）。
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshot(thread({ threadId: THREAD_ID, status: 'IDLE', processing: false })),
    )
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(THREAD_ID, null, 'root thread', false),
      treeNode(CHILD_THREAD_ID, THREAD_ID, 'worker', true),
    ])
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: thread({ executionControl: 'STOPPED' }),
      stoppedThreads: [],
    })
    bindPaneToRoot()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })

    const composer = await screen.findByLabelText('给 AI 发送消息')
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalled())
    await user.click(composer)
    await user.keyboard('/stop{Enter}')

    await waitFor(() => {
      expect(harnessService.stopThread).toHaveBeenCalledWith(
        THREAD_ID,
        expect.objectContaining({
          expectedVersion: expect.any(String),
          stopRequestId: expect.any(String),
        }),
      )
    })
  })

  it('keeps the root interaction refresh failure visible while stale cards stay actionable', async () => {
    // 有旧数据时刷新失败不能被吞掉：错误与重试可见，已取回的卡片仍然可用。
    const user = userEvent.setup()
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(
      async (threadId: string) => snapshotForThread(threadId),
    )
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(THREAD_ID, null, 'root thread', false),
      treeNode(CHILD_THREAD_ID, THREAD_ID, 'worker', true),
    ])
    vi.mocked(interactionService.listInteractions).mockResolvedValue({
      items: [approvalInteraction(CHILD_THREAD_ID)],
      nextCursor: null,
    })
    bindPaneToRoot()
    const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })

    await screen.findByRole('button', { name: '允许' })

    vi.mocked(interactionService.listInteractions).mockRejectedValue(new Error('interactions down'))
    await act(async () => {
      await view.client.invalidateQueries({ queryKey: queryKeys.interactions.all })
    })

    expect(await screen.findByRole('alert')).toHaveTextContent('待处理交互加载失败')
    expect(screen.getByRole('button', { name: '允许' })).toBeInTheDocument()

    // 重试入口真的重新拉取，而不是只显示文案。
    const callsBeforeRetry = vi.mocked(interactionService.listInteractions).mock.calls.length
    vi.mocked(interactionService.listInteractions).mockResolvedValue({
      items: [approvalInteraction(CHILD_THREAD_ID)],
      nextCursor: null,
    })
    await user.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(vi.mocked(interactionService.listInteractions).mock.calls.length)
      .toBeGreaterThan(callsBeforeRetry))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })
})

describe('AgentPane root control mounting', () => {
  beforeEach(resetMountProbes)

  const CHILD_THREAD_ID = '22222222-3333-4444-8555-666666666666'
  const NEW_ROOT_ID = '33333333-4444-4555-8666-777777777777'
  const PANE_TARGET_KEY = `kk-studio.agent-pane-target.CHAT:${CHAT_ID}:pane-1`

  function bindPaneTarget(target: unknown) {
    localStorage.setItem(PANE_TARGET_KEY, JSON.stringify(target))
  }

  function childSnapshot() {
    return snapshot(threadFixture(CHILD_THREAD_ID, {
      name: 'worker child',
      parentThreadId: THREAD_ID,
    }))
  }

  function pendingInvocation(): ToolInvocationDTO {
    return {
      id: 'waiting-tool', modelInvocationId: 'model-1', assistantEntryId: 'assistant-1', callIndex: 0,
      status: 'READY', attempt: 1, toolCallId: 'call-1', toolName: 'bash', rendererKey: 'bash',
      environmentId: null, requiredEnvironmentId: 'frozen-env', waitingForEnvironment: false,
      requiredEnvironmentName: 'archlinux', environmentWaitFreshnessAt: null,
      argumentsJson: '{"command":"pwd"}', approvalJson: null, resultJson: null, errorJson: null,
      createTime: null, updateTime: null,
    }
  }

  it.each([
    ['READY', false, '排队中'],
    ['READY', true, '等待环境 archlinux 上线'],
    ['WAITING_APPROVAL', false, '等待审批'],
    ['WAITING_INPUT', false, '等待输入'],
    ['DISPATCHING', false, '分派中'],
  ])('working 展示 %s 的真实等待而不是通用执行中', async (status, waiting, label) => {
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(
      thread({ status: 'TOOL_READY', processing: true }),
      { toolInvocations: [{
        ...pendingInvocation(), status, waitingForEnvironment: waiting,
        requiredEnvironmentId: 'frozen-env', requiredEnvironmentName: 'archlinux',
      }] },
    ))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await waitFor(() => expect(document.querySelector('.thread-working')).toHaveTextContent(label))
    expect(document.querySelector('.thread-working')).not.toHaveTextContent('执行中')
    expect(document.querySelector('.thread-status-footer')).not.toHaveTextContent('等待环境')
  })

  it('真实 RUNNING sibling 不被另一个环境等待覆盖为整条 Thread 等待', async () => {
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(
      thread({ status: 'TOOL_RUNNING', processing: true }),
      { toolInvocations: [
        { ...pendingInvocation(), status: 'READY', waitingForEnvironment: true,
          requiredEnvironmentId: 'frozen-env', requiredEnvironmentName: 'archlinux' },
        { ...pendingInvocation(), id: 'running-sibling', status: 'RUNNING' },
      ] },
    ))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await waitFor(() => expect(document.querySelector('.thread-working')).toHaveTextContent('执行中'))
  })

  it('never mounts root control for an unconfirmed bound target', async () => {
    // 绑定目标的身份还没确认时可能是子代理：不接受任何草稿、上传与人工执行 Hook。
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: CHILD_THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockReturnValue(new Promise(() => {}))

    renderPane({ type: 'CHAT', chatId: CHAT_ID })

    await waitFor(() =>
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(CHILD_THREAD_ID))
    expect(controlMounts.mount).not.toHaveBeenCalled()
    expect(controlMounts.unmount).not.toHaveBeenCalled()
    expect(uploadMounts.mount).not.toHaveBeenCalled()
    expect(uploadMounts.unmount).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('给 AI 发送消息')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重命名' })).not.toBeInTheDocument()
  })

  it('stays read-only with no upload registry after a delayed child snapshot arrives', async () => {
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: CHILD_THREAD_ID })
    let resolveSnapshot: ((value: HarnessThreadSnapshotDTO) => void) | null = null
    vi.mocked(harnessService.getThreadSnapshot).mockReturnValue(
      new Promise<HarnessThreadSnapshotDTO>((resolve) => {
        resolveSnapshot = resolve
      }),
    )

    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    await waitFor(() =>
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(CHILD_THREAD_ID))

    await act(async () => {
      resolveSnapshot?.(childSnapshot())
    })

    expect(await screen.findByText('只读查看')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'worker child' })).toBeInTheDocument()
    expect(controlMounts.mount).not.toHaveBeenCalled()
    expect(controlMounts.unmount).not.toHaveBeenCalled()
    expect(uploadMounts.mount).not.toHaveBeenCalled()
    expect(uploadMounts.unmount).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('给 AI 发送消息')).not.toBeInTheDocument()
  })

  it('mounts root control exactly once for a confirmed execution root', async () => {
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread()))

    renderPane({ type: 'CHAT', chatId: CHAT_ID })

    expect(await screen.findByLabelText('给 AI 发送消息')).toBeInTheDocument()
    expect(controlMounts.mount).toHaveBeenCalledTimes(1)
    expect(controlMounts.unmount).not.toHaveBeenCalled()
    expect(uploadMounts.mount).toHaveBeenCalledTimes(1)
    expect(uploadMounts.unmount).not.toHaveBeenCalled()
  })

  it('binds an accepted new session as a known root without waiting for the snapshot', async () => {
    // 验收成功后绑定新根：身份来自验收快照种子，因此目标切换与执行根确认落在同一次
    // commit，根控制面不会因为身份未知而瞬间卸载重建（快照请求此时仍未返回）。
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockResolvedValue(
      acceptedResponse(threadFixture(NEW_ROOT_ID, { name: '新会话根' })),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) => {
      if (threadId === NEW_ROOT_ID) {
        return new Promise<HarnessThreadSnapshotDTO>(() => {})
      }
      return snapshot()
    })

    const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, '第一句')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // 身份不再由面板标题呈现（上移 workspace 顶栏）；同一次 commit 的可观察证据是
    // 验收种子已进入快照缓存，且控制面没有被卸载重挂。
    await waitFor(() => expect(
      view.client.getQueryData(queryKeys.threads.snapshot(NEW_ROOT_ID)),
    ).toBeTruthy())
    // 种进缓存的是权威 Thread 的完整快照 DTO（含 yoloPolicy），不是裁剪过的 Thread：
    // 服务端快照 schema 的每个字段都在，后续投影不会读到半个对象。
    const seeded = view.client.getQueryData<HarnessThreadSnapshotDTO>(
      queryKeys.threads.snapshot(NEW_ROOT_ID),
    )
    expect(Object.keys(seeded ?? {}).sort()).toEqual([
      'entries',
      'manualCompaction',
      'modelAttemptFailures',
      'modelInvocation',
      'queuedCommands',
      'stopReceipts',
      'thread',
      'toolInvocations',
      'version',
    ])
    expect(seeded?.thread.threadId).toBe(NEW_ROOT_ID)
    expect(seeded?.thread.yoloPolicy).toEqual({ mode: 'DISABLE', rootThreadId: null })
    expect(seeded?.version).toBe(seeded?.thread.version)
    expect(screen.getByLabelText('给 AI 发送消息')).toBeInTheDocument()
    expect(screen.queryByText('只读查看')).not.toBeInTheDocument()
    // 控制面只挂载一次且没有被卸载：绑定新根没有把 RootAgentPane 卸载重挂。
    expect(controlMounts.mount).toHaveBeenCalledTimes(1)
    expect(controlMounts.unmount).not.toHaveBeenCalled()
  })

  it('resolves timeline resources through the pane blob adapter and degrades safely', async () => {
    // 资源消息的下载/预览 URL 由 pane 的适配层解析；服务端缺字段时不伪造可预览 URL。
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    const resourceEntry = (entryId: string, blobId: string) => ({
      entryId,
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'MESSAGE',
      payloadJson: JSON.stringify({
        message: {
          role: 'USER',
          contents: [
            { type: 'text', text: '看这张图' },
            { type: 'resource', blobId, name: `${blobId}.png`, mediaType: 'image/png' },
          ],
        },
      }),
      createTime: '2026-07-28T10:00:00Z',
    })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread(), {
      entries: [resourceEntry('user-blob-1', 'blob-1'), resourceEntry('user-blob-2', 'blob-2')],
    }))

    const defaultDownload = async () => ({ url: 'https://storage.test/blob', expiresAt: null })
    fakeStorage.getBlobDownloadUrl.mockImplementation(async (blobId: string) => {
      if (blobId === 'blob-1') {
        return { url: 'https://storage.test/blob-1', mediaType: 'image/png', sizeBytes: 128, expiresAt: null }
      }
      // 第二个资源解析直接失败：适配层必须吞掉失败并退化为无预览。
      throw new Error('blob-2 unavailable')
    })
    fakeStorage.getBlobPreviewUrl.mockImplementation(async (blobId: string) => (
      blobId === 'blob-1'
        ? { url: 'https://storage.test/preview-1', mediaType: 'image/png', sizeBytes: 128, expiresAt: null }
        : (() => { throw new Error('blob-2 unavailable') })()
    ))

    try {
      renderPane({ type: 'CHAT', chatId: CHAT_ID })

      // 完整资源解析成可预览图片；解析失败的资源退化为无预览，不冒泡错误。
      expect(await screen.findByRole('img', { name: 'blob-1.png' })).toHaveAttribute(
        'src',
        'https://storage.test/preview-1',
      )
      expect(screen.getAllByRole('img')).toHaveLength(1)
      expect(fakeStorage.getBlobDownloadUrl).toHaveBeenCalledWith('blob-1')
      expect(fakeStorage.getBlobDownloadUrl).toHaveBeenCalledWith('blob-2')
    } finally {
      fakeStorage.getBlobDownloadUrl.mockReset()
      fakeStorage.getBlobDownloadUrl.mockImplementation(defaultDownload)
      fakeStorage.getBlobPreviewUrl.mockReset()
      fakeStorage.getBlobPreviewUrl.mockImplementation(async () => null)
    }
  })

  it('recalls a queued user message through the root composer history', async () => {
    // 根面板把自己的排队输入也交给历史游标：↑ 能直接取回排队中的那一条。
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread(), {
      queuedCommands: [{
        threadId: THREAD_ID,
        sequence: '1',
        type: 'USER_MESSAGE',
        state: 'QUEUED',
        idempotencyKey: 'queued-1',
        payloadJson: JSON.stringify({
          message: { role: 'USER', contents: [{ type: 'text', text: '排队中的输入' }] },
        }),
        cancelledAt: null,
        createTime: '2026-07-28T10:00:00Z',
      }],
    }))

    const user = userEvent.setup()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('{ArrowUp}')

    await waitFor(() => expect(composer).toHaveTextContent('排队中的输入'))
  })

  it('keeps the original source target without exposing a UUID when its tree name is missing', async () => {
    // 无来源名称仍保留原始跳转身份，不借用根名称，也不向用户展示裸 UUID。
    const unknownSourceId = '44444444-5555-4666-8777-888888888888'
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread()))
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(THREAD_ID, null, 'thread-name', false),
    ])
    vi.mocked(interactionService.listInteractions).mockResolvedValue({
      items: [approvalInteraction(unknownSourceId)],
      nextCursor: null,
    })

    renderPane({ type: 'CHAT', chatId: CHAT_ID })

    const source = await screen.findByRole('link', { name: '查看 subagent 执行' })
    expect(source).toHaveAttribute('title', '查看 subagent 执行')
    expect(source).not.toHaveTextContent(unknownSourceId)
    expect(source).toHaveAttribute('href', `/threads/${unknownSourceId}`)
  })

  it('keeps the full root interaction pagination reachable through load more', async () => {
    const secondSourceId = '55555555-6666-4777-8888-999999999999'
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread()))
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(THREAD_ID, null, 'thread-name', false),
      treeNode(secondSourceId, THREAD_ID, 'second worker', true),
    ])
    vi.mocked(interactionService.listInteractions)
      .mockResolvedValueOnce({
        items: [approvalInteraction(CHILD_THREAD_ID)],
        nextCursor: 'cursor-2',
      })
      .mockResolvedValueOnce({
        items: [approvalInteraction(secondSourceId)],
        nextCursor: null,
      })

    const user = userEvent.setup()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })

    await screen.findByRole('link', { name: /second worker/ })
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    // 第二页追加而不是替换，翻页入口随之消失。
    await waitFor(() => expect(screen.getAllByRole('button', { name: '允许' })).toHaveLength(2))
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(vi.mocked(interactionService.listInteractions).mock.calls[1]?.[1]).toBe('cursor-2')
  })

  it('captures a load more failure without dropping the already loaded cards', async () => {
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread()))
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      treeNode(THREAD_ID, null, 'thread-name', false),
      treeNode(CHILD_THREAD_ID, THREAD_ID, 'worker child', true),
    ])
    vi.mocked(interactionService.listInteractions)
      .mockResolvedValueOnce({
        items: [approvalInteraction(CHILD_THREAD_ID)],
        nextCursor: 'cursor-2',
      })
      .mockRejectedValueOnce(new Error('page two down'))

    const user = userEvent.setup()
    renderPane({ type: 'CHAT', chatId: CHAT_ID })

    await screen.findByRole('button', { name: '允许' })
    await user.click(screen.getByRole('button', { name: '加载更多' }))

    // 翻页失败必须被呈现（不是静默丢弃），已取回的卡片仍然可用，重试入口保留。
    expect(await screen.findByRole('alert')).toHaveTextContent('待处理交互加载失败')
    expect(screen.getByRole('button', { name: '允许' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '加载更多' })).toBeInTheDocument()
  })

  it('accepts a branch goal from a NEW_THREAD_DRAFT through the same atomic batch', async () => {
    // 草稿目标上的 Goal 与首条消息共用同一验收通道（frozen request + 绑定新根）。
    const user = userEvent.setup()
    bindPaneTarget({
      kind: 'NEW_THREAD_DRAFT',
      sessionId: 'session-1',
      startEntryId: 'entry-1',
      threadName: 'branch-1',
    })
    vi.mocked(harnessService.listSessionEntries).mockResolvedValue([{
      entryId: 'entry-1',
      sessionId: 'session-1',
      parentEntryId: null,
      entryType: 'ROOT',
      payloadJson: '{}',
      createTime: null,
    }])
    vi.mocked(harnessService.acceptCommandBatch).mockResolvedValue(
      acceptedResponse(threadFixture(NEW_ROOT_ID, { name: '新分支根' })),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) =>
      threadId === NEW_ROOT_ID
        ? snapshot(threadFixture(NEW_ROOT_ID, { name: '新分支根' }))
        : snapshot())

    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.click(composer)
    await user.keyboard('/goal{Enter}')

    const input = await screen.findByPlaceholderText('输入 thread 目标（最多 2000 字符）...')
    await user.type(input, '先把契约梳理清楚')
    await user.click(screen.getByRole('button', { name: '设置目标' }))

    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.target.type).toBe('NEW_THREAD')
    expect(JSON.stringify(request.commands)).toContain('先把契约梳理清楚')
    // 验收后绑定新根：身份展示在 workspace 顶栏，面板侧以目标完成绑定为证据。
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(NEW_ROOT_ID))
  })

  it('keeps the tree-wide Stop available and on the root when the tree query fails', async () => {
    // 执行树读取失败既不能禁用 Stop，也不能把它指向别处：本地状态与树加载无关。
    const user = userEvent.setup()
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread()))
    vi.mocked(harnessService.getThreadTree).mockRejectedValue(new Error('tree down'))
    vi.mocked(harnessService.stopThread).mockResolvedValue({
      status: 'STOPPED',
      thread: thread({ executionControl: 'STOPPED' }),
      stoppedThreads: [],
    })

    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await waitFor(() => expect(harnessService.getThreadTree).toHaveBeenCalledWith(THREAD_ID))
    await user.click(composer)
    await user.keyboard('/stop{Enter}')

    await waitFor(() => {
      expect(harnessService.stopThread).toHaveBeenCalledWith(
        THREAD_ID,
        expect.objectContaining({
          expectedVersion: expect.any(String),
          stopRequestId: expect.any(String),
        }),
      )
    })
  })

  it('keeps an already cached new-root snapshot instead of overwriting it with the seed', async () => {
    // 重放/其它 pane 已加载过该 Thread 时，验收不得用空 entries 的种子覆盖已有快照。
    const user = userEvent.setup()
    vi.mocked(harnessService.acceptCommandBatch).mockResolvedValue(
      acceptedResponse(threadFixture(NEW_ROOT_ID, { name: '新会话根' })),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId) => {
      if (threadId === NEW_ROOT_ID) {
        return new Promise<HarnessThreadSnapshotDTO>(() => {})
      }
      return snapshot()
    })

    const view = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const cached = snapshot(threadFixture(NEW_ROOT_ID, { name: '已缓存根' }), {
      entries: [{
        entryId: 'cached-entry-1',
        sessionId: 'session-1',
        parentEntryId: null,
        entryType: 'MESSAGE',
        payloadJson: JSON.stringify({
          message: { role: 'USER', contents: [{ type: 'text', text: '已有历史' }] },
        }),
        createTime: '2026-07-28T09:00:00Z',
      }],
    })
    view.client.setQueryData(queryKeys.threads.snapshot(NEW_ROOT_ID), cached)

    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, '第一句')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    // 已确认身份来自既存缓存：缓存里的名字与 entries 都没有被种子覆盖。
    await waitFor(() => expect(
      view.client.getQueryData<HarnessThreadSnapshotDTO>(queryKeys.threads.snapshot(NEW_ROOT_ID))?.thread.name,
    ).toBe('已缓存根'))
    const afterAccept = view.client.getQueryData<HarnessThreadSnapshotDTO>(
      queryKeys.threads.snapshot(NEW_ROOT_ID),
    )
    expect(afterAccept?.thread.name).toBe('已缓存根')
    expect(afterAccept?.entries).toHaveLength(1)
    expect(controlMounts.mount).toHaveBeenCalledTimes(1)
  })

  it('unmounts the previous root control when the pane target switches to an unconfirmed child', async () => {
    // 意图 A（换目标）：绑定本身变成子代理目标，旧根控制面必须卸载，草稿靠既有持久记录保留。
    const user = userEvent.setup()
    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread()))

    const first = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'root draft stays')
    await waitFor(async () =>
      expect((await loadThreadDraft(THREAD_ID))?.parts)
        .toEqual([expect.objectContaining({ type: 'text', text: 'root draft stays' })]))
    first.unmount()
    // 换目标时旧根控制面（含上传注册表）确实卸载；草稿已经在持久记录里。
    expect(controlMounts.mount).toHaveBeenCalledTimes(1)
    expect(controlMounts.unmount).toHaveBeenCalledTimes(1)
    resetMountProbes()

    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: CHILD_THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(childSnapshot())
    const second = renderPane({ type: 'CHAT', chatId: CHAT_ID })
    expect(await screen.findByText('只读查看')).toBeInTheDocument()
    expect(controlMounts.mount).not.toHaveBeenCalled()
    expect(controlMounts.unmount).not.toHaveBeenCalled()
    expect(uploadMounts.mount).not.toHaveBeenCalled()
    expect(uploadMounts.unmount).not.toHaveBeenCalled()
    // 子代理目标不会覆盖或清掉根自己的草稿记录。
    expect((await loadThreadDraft(THREAD_ID))?.parts)
      .toEqual([expect.objectContaining({ type: 'text', text: 'root draft stays' })])
    second.unmount()

    bindPaneTarget({ kind: 'BOUND_THREAD', threadId: THREAD_ID })
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot(thread()))
    renderPane({ type: 'CHAT', chatId: CHAT_ID })
    const restored = await screen.findByLabelText('给 AI 发送消息')
    // 回到根：草稿从既有持久记录恢复，不需要任何额外接线。
    await waitFor(() => expect(restored).toHaveTextContent('root draft stays'))
  })
})
