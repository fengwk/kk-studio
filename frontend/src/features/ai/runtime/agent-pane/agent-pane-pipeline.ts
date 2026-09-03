import type {
  AgentCommandBatchRequestDTO,
  AgentRuntimeOwnerDTO,
  HarnessBranchSettingsDTO,
  HarnessCommandCreateDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'
import {
  buildBranchDiffCommands,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  hasMessageContent,
  partsKey,
  partsToMessageContents,
  trimMessageParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import { createUuid } from '@/shared/lib/uuid'
import { ApiError, isConflictError, isNotFoundError } from '@/shared/api/client'
import {
  samePaneTarget,
  type PaneTarget,
} from '@/features/ai/runtime/agent-pane/pane-target'

export interface AcceptanceBuildInput {
  owner: AgentRuntimeOwnerDTO
  target: PaneTarget
  draft: BranchDraft
  base: BranchDraft
  /** Resolved payload parts used to build the durable USER_MESSAGE request. */
  parts: ComposerPart[]
  /** Browser-local draft identity restored after a definite failure or explicit abandon. */
  localParts?: ComposerPart[]
  thread?: HarnessThreadDTO | null
  createId?: () => string
}

export interface FrozenCommandBatchRequest {
  owner: AgentRuntimeOwnerDTO
  target: PaneTarget
  request: AgentCommandBatchRequestDTO
  branchDraft: BranchDraft
  composerParts: ComposerPart[]
  identity: string
}

export function createIdempotencyKey(): string {
  return createUuid()
}

export function createSessionId(): string {
  return createUuid()
}

export function createThreadId(): string {
  return createUuid()
}

export function createBranchSettings(draft: BranchDraft): HarnessBranchSettingsDTO {
  return {
    workspacePath: draft.workspacePath,
    agentName: draft.agentName,
    model: { ...draft.model },
  }
}

/**
 * Builds the complete frozen request before the network call. NEW_SESSION intentionally
 * contains only USER_MESSAGE: the final draft is applied directly into rootSettings.
 */
export function buildAcceptanceRequest(input: AcceptanceBuildInput): FrozenCommandBatchRequest {
  const createId = input.createId ?? createIdempotencyKey
  const payloadParts = trimMessageParts(input.parts)
  const composerParts = trimMessageParts(input.localParts ?? input.parts)
  if (!hasMessageContent(payloadParts)) {
    throw new Error('A command batch requires message content')
  }
  const contents = partsToMessageContents(payloadParts)
  if (contents.length === 0) {
    throw new Error('A command batch requires non-empty contents')
  }
  const firstContent = contents[0]
  if (firstContent == null) {
    throw new Error('A command batch requires non-empty contents')
  }
  const message: HarnessCommandCreateDTO = {
    type: 'USER_MESSAGE',
    idempotencyKey: createId(),
    contents: [firstContent, ...contents.slice(1)],
  }
  const commands =
    input.target.kind === 'NEW_SESSION_DRAFT'
      ? [message]
      : [
          ...buildBranchDiffCommands(input.base, input.draft, createId),
          message,
        ]
  const target = buildTarget(input.target, input.draft, input.thread)
  const request: AgentCommandBatchRequestDTO = {
    owner: { ...input.owner },
    target,
    commands,
  }
  return {
    owner: { ...input.owner },
    target: input.target,
    request,
    branchDraft: copyBranchDraft(input.draft),
    composerParts,
    identity: JSON.stringify({
      owner: input.owner,
      target: input.target,
      commands: commands.map((command) => command.type === 'USER_MESSAGE'
        ? { ...command, idempotencyKey: undefined }
        : { ...command, idempotencyKey: undefined }),
      branchDraft: input.draft,
      parts: partsKey(payloadParts),
    }),
  }
}

function buildTarget(
  target: PaneTarget,
  draft: BranchDraft,
  thread: HarnessThreadDTO | null | undefined,
) {
  if (target.kind === 'NEW_SESSION_DRAFT') {
    return {
      type: 'NEW_SESSION' as const,
      sessionId: createSessionId(),
      threadId: createThreadId(),
      rootSettings: createBranchSettings(draft),
      yoloEnabled: draft.yoloEnabled,
    }
  }
  if (target.kind === 'ENTRY_DRAFT') {
    return {
      type: 'ENTRY' as const,
      sessionId: target.sessionId,
      startEntryId: target.startEntryId,
      threadId: createThreadId(),
      yoloEnabled: draft.yoloEnabled,
    }
  }
  if (thread == null || thread.threadId !== target.threadId) {
    throw new Error('Bound Thread snapshot is not available')
  }
  return {
    type: 'THREAD' as const,
    threadId: thread.threadId,
    expectedHeadEntryId: thread.headEntryId,
    expectedNextCommandSequence: thread.nextCommandSequence,
  }
}

export function copyBranchDraft(draft: BranchDraft): BranchDraft {
  return {
    ...draft,
    model: { ...draft.model },
  }
}

/**
 * Exact retry must reuse the original request object. This helper is deliberately
 * identity based so callers cannot accidentally rebuild ids/cursors.
 */
export function sameFrozenRequest(
  left: FrozenCommandBatchRequest,
  right: FrozenCommandBatchRequest,
): boolean {
  return left.identity === right.identity
    && JSON.stringify(left.request) === JSON.stringify(right.request)
}

export function acceptanceCompletionApplies(
  currentTarget: PaneTarget,
  pending: { target: PaneTarget; generation: number },
  currentGeneration: number,
): boolean {
  return currentGeneration === pending.generation
    && samePaneTarget(currentTarget, pending.target)
}

export function isDefiniteAcceptanceFailure(error: unknown): boolean {
  if (error instanceof ApiError) {
    return error.status != null && error.status >= 400 && error.status < 500
  }
  return false
}

export function isUnknownAcceptanceOutcome(error: unknown): boolean {
  return !isDefiniteAcceptanceFailure(error)
}

export function acceptanceConflictReason(error: unknown): string | null {
  if (!isConflictError(error)) {
    return null
  }
  const reason = error instanceof ApiError ? error.errors?.reason : undefined
  return typeof reason === 'string' && reason.trim() ? reason : 'CONFLICT'
}

export function shouldRefreshAfterAcceptanceFailure(error: unknown): boolean {
  return isConflictError(error) || isNotFoundError(error)
}

/**
 * The frozen local draft is prepended on a definite failure. If the user typed
 * after the request started, that newer input remains after it and is never lost.
 */
export function prependFrozenComposerParts(
  frozen: ComposerPart[],
  current: ComposerPart[],
): ComposerPart[] {
  if (frozen.length === 0) {
    return current
  }
  if (partsKey(frozen) === partsKey(current)) {
    return current
  }
  return [...frozen, ...current]
}

export function preserveCurrentBranchDraft(
  frozen: BranchDraft,
  current: BranchDraft | null,
): BranchDraft {
  return current == null ? copyBranchDraft(frozen) : current
}
