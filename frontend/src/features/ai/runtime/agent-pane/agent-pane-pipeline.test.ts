import { describe, expect, it } from 'vitest'
import {
  acceptanceCompletionApplies,
  acceptanceConflictReason,
  buildAcceptanceRequest,
  copyBranchDraft,
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
  environment: null,
  agentName: 'assistant',
  model: { providerName: 'provider', modelName: 'model', variant: 'default' },
  yoloEnabled: false,
}

const thread: HarnessThreadDTO = {
  threadId: '11111111-2222-4333-8444-555555555555',
  sessionId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
  headEntryId: '99999999-8888-4777-8666-555555555555',
  yoloEnabled: false,
  nextCommandSequence: '9',
  version: '7',
  status: 'IDLE',
  processing: false,
  branchSettings: {
    environment: null,
    agentName: 'assistant',
    model: { providerName: 'provider', modelName: 'model', variant: 'default' },
  },
  createTime: null,
  updateTime: null,
}

describe('AgentPane acceptance pipeline', () => {
  it('creates NEW_SESSION in one request with root settings and only USER_MESSAGE', () => {
    const plan = buildAcceptanceRequest({
      owner: { type: 'CHAT', id: 'chat-1' },
      target: { kind: 'NEW_SESSION_DRAFT' },
      draft: baseDraft,
      base: baseDraft,
      parts: [createTextPart('hello')],
      createId: () => 'command-1',
    })

    expect(plan.request.owner).toEqual({ type: 'CHAT', id: 'chat-1' })
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
      },
      yoloEnabled: false,
    })
  })

  it('uses the same setting diff for ENTRY and THREAD targets', () => {
    const draft = { ...baseDraft, agentName: 'coder', yoloEnabled: true }
    const entry = buildAcceptanceRequest({
      owner: { type: 'CANVAS', id: 'canvas-1' },
      target: { kind: 'ENTRY_DRAFT', sessionId: 's1', startEntryId: 'e1' },
      draft,
      base: baseDraft,
      parts: [createTextPart('continue')],
      createId: (() => {
        let index = 0
        return () => `id-${index++}`
      })(),
    })
    const bound = buildAcceptanceRequest({
      owner: { type: 'CANVAS', id: 'canvas-1' },
      target: { kind: 'BOUND_THREAD', threadId: thread.threadId },
      thread,
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
    expect(bound.request.commands.map((command) => command.type)).toEqual([
      'SET_AGENT',
      'USER_MESSAGE',
    ])
    expect(entry.request.target).toMatchObject({
      type: 'ENTRY',
      sessionId: 's1',
      startEntryId: 'e1',
    })
    expect(bound.request.target).toEqual({
      type: 'THREAD',
      threadId: thread.threadId,
      expectedHeadEntryId: thread.headEntryId,
      expectedNextCommandSequence: thread.nextCommandSequence,
    })
  })

  it('keeps exact unknown retries and fences late success after a target switch', () => {
    const plan = buildAcceptanceRequest({
      owner: { type: 'CHAT', id: 'chat-1' },
      target: { kind: 'NEW_SESSION_DRAFT' },
      draft: baseDraft,
      base: baseDraft,
      parts: [createTextPart('retry me')],
      createId: () => 'stable-command',
    })
    expect(plan.request.commands[0]?.idempotencyKey).toBe('stable-command')
    expect(isUnknownAcceptanceOutcome(new Error('timeout'))).toBe(true)
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
        { kind: 'ENTRY_DRAFT', sessionId: 's1', startEntryId: 'e2' },
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
      owner: { type: 'CHAT', id: 'chat-1' },
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
      owner: { type: 'CHAT', id: 'chat-1' },
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
      owner: { type: 'CHAT', id: 'chat-1' },
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

  it('projects one command matrix for Chat and Canvas across all three targets', () => {
    const expected = {
      NEW_SESSION_DRAFT: ['thread', 'agent', 'environment', 'yolo', 'models', 'upload', 'shortcuts'],
      ENTRY_DRAFT: ['thread', 'agent', 'environment', 'yolo', 'models', 'tree', 'new', 'upload', 'shortcuts'],
      BOUND_THREAD: THREAD_COMMANDS.map((command) => command.id),
    } as const
    for (const kind of Object.keys(expected) as Array<keyof typeof expected>) {
      const target = kind === 'NEW_SESSION_DRAFT'
        ? { kind }
        : kind === 'ENTRY_DRAFT'
          ? { kind, sessionId: 's1', startEntryId: 'e1' }
          : { kind, threadId: thread.threadId }
      const commands = threadCommandsForTarget(target)
      expect(commands.filter((command) => !command.disabled).map((command) => command.id))
        .toEqual(expected[kind])
    }
  })
})
