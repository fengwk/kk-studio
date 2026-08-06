import type {
  HarnessThreadCommandBatchDTO,
  HarnessThreadCommandCreateDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'
import {
  branchDraftFromThread,
  buildBranchDiffCommands,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'

export function createCommandId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `cmd-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function createStopRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `stop-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function createDecisionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `dec-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * One atomic command batch plan: the exact POST body plus a semantic identity used to decide
 * whether a failed HTTP request may be replayed byte-for-byte.
 *
 * The identity is the IMMUTABLE user intent: thread + message content + target draft. It never
 * includes the effective base, because queued SET_* commands project a pending target into the
 * base: after a failed batch is accepted, the next snapshot may show base === draft while the
 * user made no edit — the retry must still hit the original identity and replay the exact batch
 * (same IDs/payload/order and original expected cursors) instead of minting a USER_MESSAGE-only
 * batch that would duplicate the message. Editing content or the target draft changes the
 * identity and always produces fresh IDs.
 */
export interface CommandBatchPlan {
  batch: HarnessThreadCommandBatchDTO
  identity: string
}

/**
 * Builds the send batch for an existing Thread: minimal settings diff against the effective
 * base (durable base projected through QUEUED settings), followed by the USER_MESSAGE command.
 * The CAS cursors come from the latest snapshot Thread DTO.
 */
export function buildMessageBatchPlan(options: {
  thread: HarnessThreadDTO
  effectiveBase: BranchDraft
  draft: BranchDraft
  content: string
  createCommandId?: () => string
}): CommandBatchPlan {
  const createId = options.createCommandId ?? createCommandId
  const settingsCommands = buildBranchDiffCommands(options.effectiveBase, options.draft, createId)
  // USER_MESSAGE must NOT carry role: the strict mapper forbids it (role is always USER).
  const messageCommand: HarnessThreadCommandCreateDTO = {
    type: 'USER_MESSAGE',
    clientCommandId: createId(),
    content: options.content,
  }
  const commands = [...settingsCommands, messageCommand]
  return {
    batch: {
      expectedHeadEntryId: options.thread.headEntryId,
      expectedNextCommandSequence: options.thread.nextCommandSequence,
      commands,
    },
    // Immutable user intent: thread + content + target draft. effectiveBase is deliberately
    // absent — queued SET_* projection mutates it, but the user intent did not change.
    identity: JSON.stringify({
      threadId: options.thread.threadId,
      content: options.content,
      draft: options.draft,
    }),
  }
}

/**
 * First-send batch against a freshly created Thread: only the USER_MESSAGE command; the full
 * branch draft was already baked into the Thread creation.
 *
 * The semantic identity shares the immutable-intent structure of {@link buildMessageBatchPlan}
 * ({threadId, content, draft}): a failed first send replayed through a bound pane (same thread,
 * same content, same target draft) produces an identical identity and reuses the exact batch
 * byte-for-byte, preserving the message command id.
 */
export function buildFirstSendMessagePlan(options: {
  thread: HarnessThreadDTO
  content: string
  clientCommandId?: string
}): CommandBatchPlan {
  // USER_MESSAGE must NOT carry role: the strict mapper forbids it (role is always USER).
  const messageCommand: HarnessThreadCommandCreateDTO = {
    type: 'USER_MESSAGE',
    clientCommandId: options.clientCommandId ?? createCommandId(),
    content: options.content,
  }
  const base = branchDraftFromThread(options.thread)
  return {
    batch: {
      expectedHeadEntryId: options.thread.headEntryId,
      expectedNextCommandSequence: options.thread.nextCommandSequence,
      commands: [messageCommand],
    },
    identity: JSON.stringify({
      threadId: options.thread.threadId,
      content: options.content,
      draft: base,
    }),
  }
}
