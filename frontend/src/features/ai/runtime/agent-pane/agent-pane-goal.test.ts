import { describe, expect, it } from 'vitest'
import { buildGoalAcceptanceRequest } from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import { buildGoalBatchPlan } from '@/features/ai/chat/command-batch-plan'
import {
  savePendingAcceptance,
  loadPendingAcceptance,
} from '@/features/ai/runtime/agent-pane/pane-target'
import {
  THREAD_COMMANDS,
  threadCommandsForTarget,
} from '@/features/ai/runtime/thread-panel/thread-commands'
import { createTextPart, createAttachmentPart } from '@/features/ai/composer/composer-parts'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import type { AgentRuntimeOwnerDTO, HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'

const baseDraft: BranchDraft = {
  agentName: 'assistant',
  model: { providerName: 'provider', modelName: 'model', variant: 'default' },
  environmentName: null,
  yoloEnabled: false,
}

const mockThread: HarnessThreadDTO = {
  threadId: 't-123',
  name: 'Bound thread',
  sessionId: 's-123',
  headEntryId: 'e-1',
  parentThreadId: null,
  yoloEnabled: false,
  nextCommandSequence: '1',
  version: '1',
  status: 'IDLE',
  processing: false,
  executionControl: 'RUNNABLE',
  branchSettings: {
    agentName: 'assistant',
    model: { providerName: 'provider', modelName: 'model', variant: 'default' },
    environmentName: null,
    goal: { id: 'existing-goal-id', text: 'Existing goal text' },
  },
  createTime: '2026-09-24T10:00:00Z',
  updateTime: '2026-09-24T10:00:00Z',
}

describe('AgentPane Goal pipeline and owner-free command availability', () => {
  describe('owner-free command availability', () => {
    it('exposes the full BOUND_THREAD command set without any owner option', () => {
      // owner 不再参与既有 Thread 的命令裁剪：无 owner 选项时仍是完整命令集。
      const commands = threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't-1' })
      expect(commands.map((command) => command.id)).toEqual(
        THREAD_COMMANDS.map((command) => command.id),
      )
      expect(commands.find((command) => command.id === 'goal')?.disabled).toBe(false)
      expect(commands.find((command) => command.id === 'stop')?.disabled).toBe(false)
    })

    it('keeps goal available on a bound thread regardless of explicit capability options', () => {
      // 命令可用性只由目标 kind 与显式能力选项决定；owner（如受控 Issue Agent）
      // 不再隐藏 goal，只有对应能力选项才禁用对应命令。
      const plain = threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't-1' })
      const noBranching = threadCommandsForTarget(
        { kind: 'BOUND_THREAD', threadId: 't-1' },
        { allowBranching: false },
      )
      expect(plain.find((command) => command.id === 'goal')?.disabled).toBe(false)
      // 禁止分支仍然生效，且只作用于导航命令，不触及 goal。
      expect(noBranching.find((command) => command.id === 'thread')?.disabled).toBe(true)
      expect(noBranching.find((command) => command.id === 'new')?.disabled).toBe(true)
      expect(noBranching.find((command) => command.id === 'goal')?.disabled).toBe(false)
    })

    it('scopes goal by the target kind instead of the owner', () => {
      // 新建 Session 没有可绑定的 Thread，goal 被目标 kind 排除；
      // 新建 Thread 与既有 Thread 共享同一 branch 语义，goal 可用。
      const sessionDraft = threadCommandsForTarget({ kind: 'NEW_SESSION_DRAFT' })
      const threadDraft = threadCommandsForTarget({
        kind: 'NEW_THREAD_DRAFT',
        sessionId: 's1',
        startEntryId: 'e1',
      })
      const bound = threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' })
      expect(sessionDraft.find((command) => command.id === 'goal')?.disabled).toBe(true)
      expect(threadDraft.find((command) => command.id === 'goal')?.disabled).toBe(false)
      expect(bound.find((command) => command.id === 'goal')?.disabled).toBe(false)
    })
  })

  describe('creation targets keep the owner contract', () => {
    it('rejects a bound thread target in favor of the thread command batch contract', () => {
      // 既有 Thread 的 Goal 必须走无 owner 的 thread command batch；
      // 创建构造器对 BOUND_THREAD 失败关闭，防止伪造 owner/target。
      expect(() =>
        buildGoalAcceptanceRequest({
          owner: { type: 'CHAT', chatId: 'chat-1' },
          target: { kind: 'BOUND_THREAD', threadId: 't-123' },
          draft: baseDraft,
          base: baseDraft,
          goalText: 'New goal',
        }),
      ).toThrow('Existing threads submit through the thread command batch contract')
    })

    it('always preserves rootSettings.goal as null on NEW_SESSION target', () => {
      const owner: AgentRuntimeOwnerDTO = { type: 'CHAT', chatId: 'chat-1' }
      const frozen = buildGoalAcceptanceRequest({
        owner,
        target: { kind: 'NEW_SESSION_DRAFT' },
        draft: baseDraft,
        base: baseDraft,
        goalText: 'Initial goal',
        createId: () => 'ns-goal-id',
      })

      // 创建请求仍然必须携带产品 owner。
      expect(frozen.request.owner).toEqual(owner)
      expect(frozen.request.target.type).toBe('NEW_SESSION')
      if (frozen.request.target.type === 'NEW_SESSION') {
        expect(frozen.request.target.rootSettings).toEqual({
          agentName: 'assistant',
          model: baseDraft.model,
          environmentName: null,
          goal: null,
        })
      }
      expect(frozen.request.commands[0]).toEqual({
        type: 'GOAL',
        idempotencyKey: 'ns-goal-id',
        text: 'Initial goal',
      })
    })
  })

  describe('buildGoalBatchPlan', () => {
    it('emits typed GOAL command in an owner-free bound-thread batch', () => {
      const plan = buildGoalBatchPlan({
        thread: mockThread,
        effectiveBase: baseDraft,
        draft: baseDraft,
        goalText: 'Deploy feature to production',
        createCommandId: () => 'fixed-goal-id',
      })

      // 既有 Thread 的写请求只携带精确 CAS 游标与有序命令，绝不伪造 owner/target。
      expect(plan.request).toEqual({
        expectedHeadEntryId: 'e-1',
        expectedNextCommandSequence: '1',
        commands: [
          {
            type: 'GOAL',
            idempotencyKey: 'fixed-goal-id',
            text: 'Deploy feature to production',
          },
        ],
      })
      expect(plan.request).not.toHaveProperty('owner')
      expect(plan.request).not.toHaveProperty('target')
    })

    it('emits typed GOAL with null text when clearing a goal on a bound thread', () => {
      const plan = buildGoalBatchPlan({
        thread: mockThread,
        effectiveBase: baseDraft,
        draft: baseDraft,
        goalText: null,
        createCommandId: () => 'clear-goal-key',
      })

      expect(plan.request.commands).toHaveLength(1)
      expect(plan.request.commands[0]).toEqual({
        type: 'GOAL',
        idempotencyKey: 'clear-goal-key',
        text: null,
      })
      expect(plan.request).not.toHaveProperty('owner')
    })
  })

  describe('Pending acceptance persistence and replay', () => {
    it('persists and reloads frozen goal acceptance request with typed GOAL command and attachments intact', () => {
      const storage: Storage = (() => {
        const store = new Map<string, string>()
        return {
          getItem: (key: string) => store.get(key) ?? null,
          setItem: (key: string, val: string) => store.set(key, val),
          removeItem: (key: string) => store.delete(key),
          clear: () => store.clear(),
          key: (idx: number) => Array.from(store.keys())[idx] ?? null,
          get length() { return store.size },
        }
      })()

      const owner: AgentRuntimeOwnerDTO = { type: 'CHAT', chatId: 'chat-1' }
      const attachment = createAttachmentPart('upload-uuid', 'test.png')
      const textPart = createTextPart('/goal New goal')

      // 只有容器创建才冻结带 owner 的请求；既有 Thread 的 Goal 走 thread command batch。
      const frozen = buildGoalAcceptanceRequest({
        owner,
        target: { kind: 'NEW_THREAD_DRAFT', sessionId: 's-123', startEntryId: 'e-1' },
        draft: baseDraft,
        base: baseDraft,
        goalText: 'New goal',
        localParts: [attachment, textPart],
        createId: () => 'frozen-goal-id',
      })

      const pending = {
        owner,
        target: frozen.target,
        request: frozen.request,
        branchDraft: frozen.branchDraft,
        composerParts: frozen.composerParts,
        generation: 1,
        unknownOutcome: true,
      }

      savePendingAcceptance(owner, 'pane-1', pending, storage)
      const loaded = loadPendingAcceptance(owner, 'pane-1', storage)

      expect(loaded).toEqual(pending)
      expect(loaded?.request.commands[0]).toEqual({
        type: 'GOAL',
        idempotencyKey: 'frozen-goal-id',
        text: 'New goal',
      })
      expect(loaded?.composerParts).toHaveLength(2)
      expect(loaded?.composerParts[0].type).toBe('attachment')
    })
  })
})
