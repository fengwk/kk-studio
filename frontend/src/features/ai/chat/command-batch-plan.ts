import { createUuid } from '@/shared/lib/uuid'
import type {
  HarnessThreadCommandBatchDTO,
  HarnessThreadCommandCreateDTO,
  HarnessThreadDTO,
  HarnessUserMessageContentDTO,
} from '@/shared/api/contracts/ai-runtime'
import {
  branchDraftFromThread,
  buildBranchDiffCommands,
  copyBinding,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  partsToMessageContents,
  trimMessageParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'

export function createCommandId(): string {
  return createUuid()
}

export function createStopRequestId(): string {
  return createUuid()
}

export function createDecisionId(): string {
  return createUuid()
}

/**
 * 原子 command batch 计划：精确的 POST body 加上用于判定失败的 HTTP 请求
 * 是否可以逐字节回放的语义身份。
 *
 * 身份是不可变的用户意图：thread + 消息内容 + target draft。它绝不包含
 * effective base，因为排队的 SET_* 命令会把 pending target 投影进 base：
 * 失败的 batch 被接受后，下一次 snapshot 可能显示 base === draft，而用户
 * 并未做任何编辑——重试仍必须命中原始身份并逐字节回放精确 batch
 *（相同的 ID/payload/顺序和原始 expected cursor），而不是铸造一个仅含
 * USER_MESSAGE 的 batch 导致消息重复。编辑内容或 target draft 会改变
 * 身份，并总是产生全新的 ID。
 */
export interface CommandBatchPlan {
  batch: HarnessThreadCommandBatchDTO
  identity: string
  /** 用户提交时的完整 branch target；cursor 恢复不得悄悄切换 Agent/Model/Environment。 */
  targetDraft: BranchDraft
}

/**
 * USER_MESSAGE 序列化 ordered contents：所有消息（含纯文本）都使用 `contents`
 * 数组——TEXT parts 与 ATTACHMENT {uploadId} 按 ComposerPart 顺序排列。
 * USER_MESSAGE 绝不携带 role（strict mapper 会拒绝它）。contents 必须非空
 *（调用方已用 hasMessageContent 保证至少一个可发送 part）。
 */
function createUserMessageCommand(
  parts: ComposerPart[],
  clientCommandId: string,
): HarnessThreadCommandCreateDTO {
  const contents = partsToMessageContents(parts)
  if (contents.length === 0) {
    throw new Error('USER_MESSAGE contents must not be empty')
  }
  return {
    type: 'USER_MESSAGE',
    clientCommandId,
    contents: contents as [HarnessUserMessageContentDTO, ...HarnessUserMessageContentDTO[]],
  }
}

/** 消息身份：`{threadId, contents, draft}`——ordered contents 是 wire 的唯一表示。 */
function messageIdentity(
  threadId: string,
  trimmed: ComposerPart[],
  draft: BranchDraft,
): string {
  return JSON.stringify({
    threadId,
    contents: partsToMessageContents(trimmed),
    draft,
  })
}

function copyBranchDraft(draft: BranchDraft): BranchDraft {
  return {
    ...draft,
    environment: copyBinding(draft.environment),
    model: { ...draft.model },
    activeTools: [...draft.activeTools],
  }
}

/**
 * 为已有 Thread 构造发送 batch：对 effective base（由持久化 base 投影穿过 QUEUED settings）
 * 取最小 settings diff，随后拼接 USER_MESSAGE command。CAS cursor 取自最新 snapshot Thread DTO。
 */
export function buildMessageBatchPlan(options: {
  thread: HarnessThreadDTO
  effectiveBase: BranchDraft
  draft: BranchDraft
  parts: ComposerPart[]
  createCommandId?: () => string
}): CommandBatchPlan {
  const createId = options.createCommandId ?? createCommandId
  const settingsCommands = buildBranchDiffCommands(options.effectiveBase, options.draft, createId)
  const trimmed = trimMessageParts(options.parts)
  const messageCommand = createUserMessageCommand(trimmed, createId())
  const commands = [...settingsCommands, messageCommand]
  return {
    batch: {
      expectedHeadEntryId: options.thread.headEntryId,
      expectedNextCommandSequence: options.thread.nextCommandSequence,
      commands,
    },
    // 不可变的用户意图：thread + ordered contents + target draft。effectiveBase 故意不参与——
    // queued SET_* 投影会改变它，但用户意图并未变化。
    identity: messageIdentity(options.thread.threadId, trimmed, options.draft),
    targetDraft: copyBranchDraft(options.draft),
  }
}

/**
 * 针对新创建 Thread 的首次发送 batch：仅含 USER_MESSAGE command；完整的
 * branch draft 已在创建 Thread 时烘焙进去。
 *
 * 语义 identity 与 {@link buildMessageBatchPlan}（{threadId, contents, draft}）共享同一
 * 不可变意图结构：首次发送失败后通过绑定面板 replay（同一 thread、同一 contents、同一
 * target draft）会得到一致的 identity，并逐字节复用同一 batch，保留 message command id。
 */
export function buildFirstSendMessagePlan(options: {
  thread: HarnessThreadDTO
  parts: ComposerPart[]
  clientCommandId?: string
}): CommandBatchPlan {
  const trimmed = trimMessageParts(options.parts)
  const messageCommand = createUserMessageCommand(
    trimmed,
    options.clientCommandId ?? createCommandId(),
  )
  const base = branchDraftFromThread(options.thread)
  return {
    batch: {
      expectedHeadEntryId: options.thread.headEntryId,
      expectedNextCommandSequence: options.thread.nextCommandSequence,
      commands: [messageCommand],
    },
    identity: messageIdentity(options.thread.threadId, trimmed, base),
    targetDraft: copyBranchDraft(base),
  }
}
