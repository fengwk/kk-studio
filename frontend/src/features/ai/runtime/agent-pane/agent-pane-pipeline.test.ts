import { describe, expect, it } from 'vitest'
import {
  acceptanceCompletionApplies,
  acceptanceConflictReason,
  buildAcceptanceRequest,
  buildThreadCommandBatchRequest,
  copyBranchDraft,
  createBranchSettings,
  isDefiniteAcceptanceFailure,
  isUnknownAcceptanceOutcome,
  prependFrozenComposerParts,
  preserveCurrentBranchDraft,
  sameFrozenRequest,
  shouldRefreshAfterAcceptanceFailure,
} from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import { threadCommandsForTarget, THREAD_COMMANDS } from '@/features/ai/runtime/thread-panel/thread-commands'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import {
  createAttachmentPart,
  createResourcePart,
  createTextPart,
} from '@/features/ai/composer/composer-parts'
import { ApiError } from '@/shared/api/client'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'

const baseDraft: BranchDraft = {
  agentName: 'assistant',
  model: { providerName: 'provider', modelName: 'model', variant: 'default' },
  environmentName: null,
  yoloEnabled: false,
}

const thread: HarnessThreadDTO = {
  threadId: '11111111-2222-4333-8444-555555555555',
  /** Thread 名称（服务端权威必填非空）。 */
  name: 'thread-name',
  sessionId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
  headEntryId: '99999999-8888-4777-8666-555555555555',
  parentThreadId: null,
  yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
  nextCommandSequence: '9',
  version: '7',
  status: 'IDLE',
  processing: false,
  executionControl: 'RUNNABLE',
  branchSettings: {
    agentName: 'assistant',
    model: { providerName: 'provider', modelName: 'model', variant: 'default' },
    environmentName: null,
  },
  createTime: null,
  updateTime: null,
}

describe('AgentPane acceptance pipeline', () => {
  it('builds one NEW_SESSION request with root settings and only USER_MESSAGE', () => {
    const plan = buildAcceptanceRequest({
      owner: { type: 'CHAT', chatId: 'chat-1' },
      target: { kind: 'NEW_SESSION_DRAFT' },
      draft: baseDraft,
      base: baseDraft,
      parts: [createTextPart('hello')],
      createId: () => 'command-1',
    })

    expect(plan.request.owner).toEqual({ type: 'CHAT', chatId: 'chat-1' })
    expect(plan.request.commands).toHaveLength(1)
    expect(plan.request.commands[0]).toMatchObject({
      type: 'USER_MESSAGE',
      idempotencyKey: 'command-1',
      contents: [{ type: 'TEXT', text: 'hello' }],
    })
    expect(plan.request.target).toMatchObject({
      type: 'NEW_SESSION',
      rootSettings: {
        agentName: 'assistant',
        model: baseDraft.model,
        environmentName: null,
      },
      yoloEnabled: false,
    })
  })

  /**
   * 测试意图：锁定 createBranchSettings 转换契约，确保 environmentName 字段完整包含（包括显式 null 与有效字符串），
   * 绝不在构建 NEW_SESSION 的 rootSettings 时丢失或丢弃。
   */
  it('includes environmentName (null and string) in createBranchSettings output', () => {
    const settingsWithNull = createBranchSettings(baseDraft)
    expect(settingsWithNull).toEqual({
      agentName: 'assistant',
      model: baseDraft.model,
      environmentName: null,
      goal: null,
    })

    const settingsWithString = createBranchSettings({
      ...baseDraft,
      environmentName: 'isolated-docker',
    })
    expect(settingsWithString).toEqual({
      agentName: 'assistant',
      model: baseDraft.model,
      environmentName: 'isolated-docker',
      goal: null,
    })
  })

  it('routes NEW_THREAD creation through settings diff and existing threads through the owner-free batch contract', () => {
    const draft = { ...baseDraft, agentName: 'coder', yoloEnabled: true }
    const entry = buildAcceptanceRequest({
      owner: { type: 'CHAT', chatId: 'chat-1' },
      target: {
        kind: 'NEW_THREAD_DRAFT',
        sessionId: 's1',
        startEntryId: 'e1',
        threadName: 'branch-1',
      },
      draft,
      base: baseDraft,
      parts: [createTextPart('continue')],
      createId: (() => {
        let index = 0
        return () => `id-${index++}`
      })(),
    })

    expect(entry.request.commands.map((command) => command.type)).toEqual([
      'SET_AGENT',
      'USER_MESSAGE',
    ])
    // 名称是创建事实的一部分：必须原样出现在 NEW_THREAD target 中，绝不初建后再改名。
    expect(entry.request.target).toMatchObject({
      type: 'NEW_THREAD',
      sessionId: 's1',
      startEntryId: 'e1',
      threadName: 'branch-1',
    })

    // 会话 fork（FORK_SESSION_DRAFT -> NEW_FORKED_SESSION）：携带来源执行根与切点，
    // 预分配客户端新 sessionId/threadId 以支撑未知结果重试幂等，且不携带 rootSettings / threadName。
    let forkIdIndex = 0
    const forked = buildAcceptanceRequest({
      owner: { type: 'CHAT', chatId: 'chat-1' },
      target: {
        kind: 'FORK_SESSION_DRAFT',
        sessionId: 'source-session',
        sourceThreadId: 'source-thread-1',
        startEntryId: 'cut-entry-1',
      },
      draft,
      base: baseDraft,
      parts: [createTextPart('fork into new session')],
      createId: () => `fork-id-${forkIdIndex++}`,
    })
    expect(forked.request.owner).toEqual({ type: 'CHAT', chatId: 'chat-1' })
    expect(forked.request.target).toMatchObject({
      type: 'NEW_FORKED_SESSION',
      sourceThreadId: 'source-thread-1',
      startEntryId: 'cut-entry-1',
      sessionId: expect.any(String),
      threadId: expect.any(String),
      yoloEnabled: true,
    })
    expect(Object.keys(forked.request.target).sort()).toEqual([
      'sessionId',
      'sourceThreadId',
      'startEntryId',
      'threadId',
      'type',
      'yoloEnabled',
    ])
    expect(forked.request.commands.map((command) => command.type)).toEqual([
      'SET_AGENT',
      'USER_MESSAGE',
    ])

    // 既有 Thread 不再伪造创建批次，必须走无 owner/target 的 thread command batch 契约。
    expect(() => buildAcceptanceRequest({
      owner: { type: 'CHAT', chatId: 'chat-1' },
      target: { kind: 'BOUND_THREAD', threadId: thread.threadId },
      draft,
      base: baseDraft,
      parts: [createTextPart('continue')],
    })).toThrow('Existing threads submit through the thread command batch contract')

    // 无 owner / 无 target 的 CAS 请求由 buildThreadCommandBatchRequest 生成。
    const commands = [{
      type: 'SET_AGENT' as const,
      idempotencyKey: 'existing-thread-1',
      agentName: 'coder',
    }]
    expect(buildThreadCommandBatchRequest(thread, commands)).toEqual({
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
      commands,
    })
  })

  it('keeps exact unknown retries and fences late success after a target switch', () => {
    const plan = buildAcceptanceRequest({
      owner: { type: 'CHAT', chatId: 'chat-1' },
      target: { kind: 'NEW_SESSION_DRAFT' },
      draft: baseDraft,
      base: baseDraft,
      parts: [createTextPart('retry me')],
      createId: () => 'stable-command',
    })
    expect(plan.request.commands[0]?.idempotencyKey).toBe('stable-command')
    expect(isUnknownAcceptanceOutcome(new Error('timeout'))).toBe(true)
    expect(isUnknownAcceptanceOutcome(new ApiError('timeout', 408))).toBe(true)
    expect(isUnknownAcceptanceOutcome(new ApiError('too many requests', 429))).toBe(true)
    expect(isDefiniteAcceptanceFailure(new ApiError('timeout', 408))).toBe(false)
    expect(isDefiniteAcceptanceFailure(new ApiError('too many requests', 429))).toBe(false)
    expect(isDefiniteAcceptanceFailure(new ApiError('stale', 409))).toBe(true)
    expect(
      acceptanceCompletionApplies(
        { kind: 'NEW_SESSION_DRAFT' },
        { target: plan.target, generation: 2 },
        2,
      ),
    ).toBe(true)
    expect(
      acceptanceCompletionApplies(
        { kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e2', threadName: 'branch-1' },
        { target: plan.target, generation: 2 },
        2,
      ),
    ).toBe(false)
  })

  it('prepends the frozen message without overwriting input typed afterwards', () => {
    const result = prependFrozenComposerParts(
      [createTextPart('frozen')],
      [createTextPart('new input')],
    )
    expect(result.map((part) => part.type === 'text' ? part.text : part.uploadId)).toEqual([
      'frozen',
      'new input',
    ])
  })

  it('freezes browser-local attachment identity separately from the resolved request payload', () => {
    const local = createAttachmentPart('local-upload-id', 'image.png')
    const resolved = { ...local, uploadId: 'server-upload-id' }

    const plan = buildAcceptanceRequest({
      owner: { type: 'CHAT', chatId: 'chat-1' },
      target: { kind: 'NEW_SESSION_DRAFT' },
      draft: baseDraft,
      base: baseDraft,
      parts: [resolved],
      localParts: [local],
      createId: () => 'attachment-command',
    })

    expect(plan.request.commands[0]).toMatchObject({
      type: 'USER_MESSAGE',
      contents: [{ type: 'ATTACHMENT', uploadId: 'server-upload-id' }],
    })
    expect(plan.composerParts).toEqual([local])
  })

  it('keeps durable RESOURCE contents in the frozen request and recovery draft', () => {
    const resource = createResourcePart('blob-1', 'report.txt', 'preview')

    const plan = buildAcceptanceRequest({
      owner: { type: 'CHAT', chatId: 'chat-1' },
      target: { kind: 'NEW_SESSION_DRAFT' },
      draft: baseDraft,
      base: baseDraft,
      parts: [resource],
      createId: () => 'resource-command',
    })

    expect(plan.request.commands[0]).toMatchObject({
      type: 'USER_MESSAGE',
      contents: [{
        type: 'RESOURCE',
        blobId: 'blob-1',
        name: 'report.txt',
        preview: 'preview',
      }],
    })
    expect(plan.composerParts).toEqual([resource])
  })

  it('keeps frozen request identity and handles definite conflict branches explicitly', () => {
    const plan = buildAcceptanceRequest({
      owner: { type: 'CHAT', chatId: 'chat-1' },
      target: { kind: 'NEW_SESSION_DRAFT' },
      draft: baseDraft,
      base: baseDraft,
      parts: [createTextPart('same request')],
      createId: () => 'same-command',
    })
    expect(sameFrozenRequest(plan, plan)).toBe(true)
    expect(sameFrozenRequest(plan, { ...plan, request: { ...plan.request, commands: [] } }))
      .toBe(false)
    expect(acceptanceConflictReason(new ApiError('conflict', 409, undefined, {
      reason: 'STALE_COMMAND_CURSOR',
    }))).toBe('STALE_COMMAND_CURSOR')
    expect(acceptanceConflictReason(new ApiError('conflict', 409))).toBe('CONFLICT')
    expect(acceptanceConflictReason(new ApiError('not conflict', 400))).toBeNull()
    expect(shouldRefreshAfterAcceptanceFailure(new ApiError('conflict', 409))).toBe(true)
    expect(shouldRefreshAfterAcceptanceFailure(new ApiError('missing', 404))).toBe(true)
    expect(shouldRefreshAfterAcceptanceFailure(new ApiError('bad request', 400))).toBe(false)
  })

  it('preserves current draft state while copying the frozen state only when needed', () => {
    const current = { ...baseDraft, agentName: 'current' }
    const currentParts = [createTextPart('current')]
    expect(prependFrozenComposerParts([], currentParts)).toBe(currentParts)
    const frozenParts = [createTextPart('frozen')]
    expect(prependFrozenComposerParts(frozenParts, frozenParts)).toBe(frozenParts)
    expect(preserveCurrentBranchDraft(baseDraft, current)).toBe(current)
    const restored = preserveCurrentBranchDraft(baseDraft, null)
    expect(restored).toEqual(copyBranchDraft(baseDraft))
    expect(restored).not.toBe(baseDraft)
  })

  it('projects one command matrix for Chat and Canvas across all four targets', () => {
    const expected = {
      NEW_SESSION_DRAFT: ['thread', 'agent', 'yolo', 'models', 'upload', 'shortcuts'],
      NEW_THREAD_DRAFT: ['thread', 'agent', 'yolo', 'models', 'history', 'new', 'upload', 'debug', 'shortcuts', 'rename-session', 'rename-thread', 'goal'],
      FORK_SESSION_DRAFT: ['thread', 'agent', 'yolo', 'models', 'history', 'new', 'upload', 'debug', 'shortcuts', 'goal'],
      BOUND_THREAD: THREAD_COMMANDS.map((command) => command.id),
    } as const
    for (const kind of Object.keys(expected) as Array<keyof typeof expected>) {
      const target = kind === 'NEW_SESSION_DRAFT'
        ? { kind }
        : kind === 'NEW_THREAD_DRAFT'
          ? { kind, sessionId: 's1', startEntryId: 'e1', threadName: 'branch-1' }
          : kind === 'FORK_SESSION_DRAFT'
            ? { kind, sessionId: 's1', sourceThreadId: 't1', startEntryId: 'e1' }
            : { kind, threadId: thread.threadId }
      const commands = threadCommandsForTarget(target)
      expect(commands.filter((command) => !command.disabled).map((command) => command.id))
        .toEqual(expected[kind])
    }
  })
})
