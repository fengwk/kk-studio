import type {
  HarnessCommandCreateDTO,
  HarnessThreadDTO,
  ThreadCommandBatchRequestDTO,
} from '@/shared/api/contracts/ai-runtime'
import {
  buildBranchDiffCommands,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  buildThreadCommandBatchRequest,
  copyBranchDraft,
  normalizeGoalText,
} from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import {
  hasMessageContent,
  partsKey,
  partsToMessageContents,
  trimMessageParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'

/**
 * 既有 Thread 的一次命令批次计划：请求只携带精确 CAS 游标与有序命令，
 * 不携带 owner/target；targetDraft 是失败后本地恢复用的 branch 草稿身份。
 */
export interface CommandBatchPlan {
  request: ThreadCommandBatchRequestDTO
  identity: string
  targetDraft: BranchDraft
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
  thread: HarnessThreadDTO
  effectiveBase: BranchDraft
  draft: BranchDraft
  parts: ComposerPart[]
  createCommandId?: () => string
}): CommandBatchPlan {
  const createId = options.createCommandId ?? createCommandId
  const payloadParts = trimMessageParts(options.parts)
  if (!hasMessageContent(payloadParts)) {
    throw new Error('A command batch requires message content')
  }
  const contents = partsToMessageContents(payloadParts)
  const firstContent = contents[0]
  if (firstContent == null) {
    throw new Error('A command batch requires non-empty contents')
  }
  const message: HarnessCommandCreateDTO = {
    type: 'USER_MESSAGE',
    idempotencyKey: createId(),
    contents: [firstContent, ...contents.slice(1)],
  }
  const commands = [
    ...buildBranchDiffCommands(options.effectiveBase, options.draft, createId),
    message,
  ]
  return {
    request: buildThreadCommandBatchRequest(options.thread, commands),
    identity: JSON.stringify({
      threadId: options.thread.threadId,
      commands: commands.map((command) => ({ ...command, idempotencyKey: undefined })),
      branchDraft: options.draft,
      parts: partsKey(payloadParts),
    }),
    targetDraft: copyBranchDraft(options.draft),
  }
}

export function buildGoalBatchPlan(options: {
  thread: HarnessThreadDTO
  effectiveBase: BranchDraft
  draft: BranchDraft
  goalText: string | null
  createCommandId?: () => string
}): CommandBatchPlan {
  const createId = options.createCommandId ?? createCommandId
  const text = normalizeGoalText(options.goalText)
  const goalCommand: HarnessCommandCreateDTO = {
    type: 'GOAL',
    idempotencyKey: createId(),
    text,
  }
  const commands = [
    ...buildBranchDiffCommands(options.effectiveBase, options.draft, createId),
    goalCommand,
  ]
  return {
    request: buildThreadCommandBatchRequest(options.thread, commands),
    identity: JSON.stringify({
      threadId: options.thread.threadId,
      commands: commands.map((command) => ({ ...command, idempotencyKey: undefined })),
      branchDraft: options.draft,
      goalText: text,
    }),
    targetDraft: copyBranchDraft(options.draft),
  }
}
