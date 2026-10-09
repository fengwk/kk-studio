import type {
  AgentCommandBatchRequestDTO,
  AgentRuntimeOwnerDTO,
  HarnessBranchSettingsDTO,
  HarnessCommandCreateDTO,
  HarnessThreadDTO,
  ThreadCommandBatchRequestDTO,
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

/**
 * 只有容器创建（NEW_SESSION / NEW_THREAD）需要产品 owner；既有 Thread 的发送、Goal、
 * 设置、预览一律走 {@link buildThreadCommandBatchRequest} 的无 owner 契约。
 */
export interface AcceptanceBuildInput {
  owner: AgentRuntimeOwnerDTO
  target: PaneTarget
  draft: BranchDraft
  base: BranchDraft
  /** Resolved payload parts used to build the durable USER_MESSAGE request. */
  parts: ComposerPart[]
  /** Browser-local draft identity restored after a definite failure or explicit abandon. */
  localParts?: ComposerPart[]
  createId?: () => string
}

export interface GoalAcceptanceBuildInput {
  owner: AgentRuntimeOwnerDTO
  target: PaneTarget
  draft: BranchDraft
  base: BranchDraft
  goalText: string | null
  localParts?: ComposerPart[]
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
    agentName: draft.agentName,
    model: { ...draft.model },
    environmentName: draft.environmentName,
    goal: null,
  }
}

/**
 * Builds the complete frozen creation request before the network call. NEW_SESSION
 * intentionally contains only USER_MESSAGE: the final draft is encoded directly in
 * rootSettings.
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
  const target = buildCreationTarget(input.target, input.draft)
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

export function buildGoalAcceptanceRequest(input: GoalAcceptanceBuildInput): FrozenCommandBatchRequest {
  const createId = input.createId ?? createIdempotencyKey
  const text = normalizeGoalText(input.goalText)
  const goalCommand: HarnessCommandCreateDTO = {
    type: 'GOAL',
    idempotencyKey: createId(),
    text,
  }
  const commands =
    input.target.kind === 'NEW_SESSION_DRAFT'
      ? [goalCommand]
      : [
          ...buildBranchDiffCommands(input.base, input.draft, createId),
          goalCommand,
        ]
  const target = buildCreationTarget(input.target, input.draft)
  const request: AgentCommandBatchRequestDTO = {
    owner: { ...input.owner },
    target,
    commands,
  }
  const composerParts = trimMessageParts(input.localParts ?? [])
  return {
    owner: { ...input.owner },
    target: input.target,
    request,
    branchDraft: copyBranchDraft(input.draft),
    composerParts,
    identity: JSON.stringify({
      owner: input.owner,
      target: input.target,
      commands: commands.map((command) => ({ ...command, idempotencyKey: undefined })),
      branchDraft: input.draft,
      goalText: text,
    }),
  }
}

/**
 * 既有 Thread 的通用写请求：服务端从 path 解析 Session，客户端只提交精确 CAS 游标与命令。
 * head/nextCommandSequence 必须来自 freshly 读取的权威 snapshot。
 */
export function buildThreadCommandBatchRequest(
  thread: HarnessThreadDTO,
  commands: HarnessCommandCreateDTO[],
): ThreadCommandBatchRequestDTO {
  return {
    expectedHeadEntryId: thread.headEntryId,
    expectedNextCommandSequence: thread.nextCommandSequence,
    commands,
  }
}

/** Goal 文本的 canonical 校验：非空、无首尾空白、≤2000 code points；null 表示清除。 */
export function normalizeGoalText(text: string | null): string | null {
  if (text === null) {
    return null
  }
  if (typeof text !== 'string' || text.trim().length === 0) {
    throw new Error('Goal text must not be empty')
  }
  if (text !== text.trim()) {
    throw new Error('Goal text must not contain surrounding whitespace')
  }
  if (Array.from(text).length > 2000) {
    throw new Error('Goal text must be <= 2000 code points')
  }
  return text
}

function buildCreationTarget(target: PaneTarget, draft: BranchDraft) {
  if (target.kind === 'NEW_SESSION_DRAFT') {
    return {
      type: 'NEW_SESSION' as const,
      sessionId: createSessionId(),
      threadId: createThreadId(),
      rootSettings: createBranchSettings(draft),
      yoloEnabled: draft.yoloEnabled,
    }
  }
  if (target.kind === 'NEW_THREAD_DRAFT') {
    return {
      type: 'NEW_THREAD' as const,
      sessionId: target.sessionId,
      startEntryId: target.startEntryId,
      threadId: createThreadId(),
      threadName: target.threadName,
      yoloEnabled: draft.yoloEnabled,
    }
  }
  if (target.kind === 'FORK_SESSION_DRAFT') {
    // 会话 fork：新 Session/Thread 身份在此生成；sourceThreadId 与切点由后端解析有效历史，
    // settings 从源切点 branch 推导，客户端只用 commands 表达相对切点的最小设置差分。
    return {
      type: 'NEW_FORKED_SESSION' as const,
      sourceThreadId: target.sourceThreadId,
      startEntryId: target.startEntryId,
      sessionId: createSessionId(),
      threadId: createThreadId(),
      yoloEnabled: draft.yoloEnabled,
    }
  }
  throw new Error('Existing threads submit through the thread command batch contract')
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
    if (error.status === 408 || error.status === 429) {
      return false
    }
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
