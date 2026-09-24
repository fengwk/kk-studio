import { describe, expect, it } from 'vitest'
import { buildGoalAcceptanceRequest } from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import { buildGoalBatchPlan } from '@/features/ai/chat/command-batch-plan'
import {
  savePendingAcceptance,
  loadPendingAcceptance,
} from '@/features/ai/runtime/agent-pane/pane-target'
import { threadCommandsForTarget } from '@/features/ai/runtime/thread-panel/thread-commands'
import { createTextPart, createAttachmentPart } from '@/features/ai/composer/composer-parts'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import type { AgentRuntimeOwnerDTO, HarnessThreadSnapshotDTO } from '@/shared/api/contracts/ai-runtime'

const baseDraft: BranchDraft = {
  agentName: 'assistant',
  model: { providerName: 'provider', modelName: 'model', variant: 'default' },
  environmentName: null,
  yoloEnabled: false,
}

const mockThread: HarnessThreadSnapshotDTO = {
  threadId: 't-123',
  sessionId: 's-123',
  headEntryId: 'e-1',
  nextCommandSequence: '1',
  branchSettings: {
    agentName: 'assistant',
    model: { providerName: 'provider', modelName: 'model', variant: 'default' },
    environmentName: null,
    goal: { id: 'existing-goal-id', text: 'Existing goal text' },
  },
  environmentReady: true,
  createdAt: '2026-09-24T10:00:00Z',
  updatedAt: '2026-09-24T10:00:00Z',
}

describe('AgentPane Goal pipeline and owner gating', () => {
  describe('Owner gating', () => {
    it('does not expose goal command when owner option is absent (default fail-closed)', () => {
      const commands = threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't-1' })
      expect(commands.some((c) => c.id === 'goal')).toBe(false)
    })

    it('enables goal command for CHAT and CANVAS owners', () => {
      const chatOwner: AgentRuntimeOwnerDTO = { type: 'CHAT', id: 'chat-1' }
      const canvasOwner: AgentRuntimeOwnerDTO = { type: 'CANVAS', id: 'canvas-1' }

      const chatCommands = threadCommandsForTarget(
        { kind: 'BOUND_THREAD', threadId: 't-1' },
        { owner: chatOwner },
      )
      expect(chatCommands.some((c) => c.id === 'goal')).toBe(true)

      const canvasCommands = threadCommandsForTarget(
        { kind: 'BOUND_THREAD', threadId: 't-1' },
        { owner: canvasOwner },
      )
      expect(canvasCommands.some((c) => c.id === 'goal')).toBe(true)
    })

    it('hides goal command for other owners like ISSUE_AGENT_SESSION', () => {
      const issueOwner: AgentRuntimeOwnerDTO = { type: 'ISSUE_AGENT_SESSION', id: 'issue-1' }

      for (const target of [
        { kind: 'NEW_SESSION_DRAFT' as const },
        { kind: 'NEW_THREAD_DRAFT' as const, sessionId: 's1', startEntryId: 'e1' },
        { kind: 'BOUND_THREAD' as const, threadId: 't1' },
      ]) {
        const commands = threadCommandsForTarget(target, { owner: issueOwner })
        expect(commands.some((c) => c.id === 'goal')).toBe(false)
      }
    })
  })

  describe('buildGoalBatchPlan', () => {
    it('emits typed GOAL command when setting a goal on a bound thread', () => {
      const plan = buildGoalBatchPlan({
        owner: { type: 'CHAT', id: 'chat-1' },
        thread: mockThread,
        effectiveBase: baseDraft,
        draft: baseDraft,
        goalText: 'Deploy feature to production',
        createCommandId: () => 'fixed-goal-id',
      })

      expect(plan.request.target).toEqual({
        type: 'THREAD',
        threadId: 't-123',
        expectedHeadEntryId: 'e-1',
        expectedNextCommandSequence: '1',
      })
      expect(plan.request.commands).toHaveLength(1)
      expect(plan.request.commands[0]).toEqual({
        type: 'GOAL',
        idempotencyKey: 'fixed-goal-id',
        text: 'Deploy feature to production',
      })
    })

    it('emits typed GOAL with null text when clearing a goal on a bound thread', () => {
      const plan = buildGoalBatchPlan({
        owner: { type: 'CHAT', id: 'chat-1' },
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
    })

    it('always preserves rootSettings.goal as null on NEW_SESSION target', () => {
      const frozen = buildGoalAcceptanceRequest({
        owner: { type: 'CHAT', id: 'chat-1' },
        target: { kind: 'NEW_SESSION_DRAFT' },
        draft: baseDraft,
        base: baseDraft,
        goalText: 'Initial goal',
        createId: () => 'ns-goal-id',
      })

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

      const owner: AgentRuntimeOwnerDTO = { type: 'CHAT', id: 'chat-1' }
      const attachment = createAttachmentPart('upload-uuid', 'test.png')
      const textPart = createTextPart('/goal New goal')

      const frozen = buildGoalAcceptanceRequest({
        owner,
        target: { kind: 'BOUND_THREAD', threadId: 't-123' },
        draft: baseDraft,
        base: baseDraft,
        goalText: 'New goal',
        localParts: [attachment, textPart],
        thread: mockThread,
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
