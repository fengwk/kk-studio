import type {
  AgentCommandBatchRequestDTO,
  AgentRuntimeOwnerDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'
import {
  buildAcceptanceRequest,
  buildGoalAcceptanceRequest,
  copyBranchDraft,
  type FrozenCommandBatchRequest,
} from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'

export interface CommandBatchPlan {
  request: AgentCommandBatchRequestDTO
  identity: string
  targetDraft: BranchDraft
  frozen: FrozenCommandBatchRequest
}

export function createCommandId(): string {
  return crypto.randomUUID()
}

export function createStopRequestId(): string {
  return crypto.randomUUID()
}

export function createDecisionId(): string {
  return crypto.randomUUID()
}

export function buildMessageBatchPlan(options: {
  owner: AgentRuntimeOwnerDTO
  thread: HarnessThreadDTO
  effectiveBase: BranchDraft
  draft: BranchDraft
  parts: ComposerPart[]
  createCommandId?: () => string
}): CommandBatchPlan {
  const frozen = buildAcceptanceRequest({
    owner: options.owner,
    target: { kind: 'BOUND_THREAD', threadId: options.thread.threadId },
    thread: options.thread,
    base: options.effectiveBase,
    draft: options.draft,
    parts: options.parts,
    createId: options.createCommandId ?? createCommandId,
  })
  return {
    request: frozen.request,
    identity: frozen.identity,
    targetDraft: copyBranchDraft(options.draft),
    frozen,
  }
}

export function buildGoalBatchPlan(options: {
  owner: AgentRuntimeOwnerDTO
  thread: HarnessThreadDTO
  effectiveBase: BranchDraft
  draft: BranchDraft
  goalText: string | null
  parts?: ComposerPart[]
  createCommandId?: () => string
}): CommandBatchPlan {
  const frozen = buildGoalAcceptanceRequest({
    owner: options.owner,
    target: { kind: 'BOUND_THREAD', threadId: options.thread.threadId },
    thread: options.thread,
    base: options.effectiveBase,
    draft: options.draft,
    goalText: options.goalText,
    localParts: options.parts,
    createId: options.createCommandId ?? createCommandId,
  })
  return {
    request: frozen.request,
    identity: frozen.identity,
    targetDraft: copyBranchDraft(options.draft),
    frozen,
  }
}
