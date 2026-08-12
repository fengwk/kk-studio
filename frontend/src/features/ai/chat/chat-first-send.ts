import type {
  HarnessBranchSettingsDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { CommandBatchReplay } from '@/features/ai/runtime'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import {
  buildFirstSendMessagePlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'

export interface FirstSendResult {
  threadId: string
  snapshot: HarnessThreadSnapshotDTO
  /** 创建后发送的精确 USER_MESSAGE-only batch（replay identity）。 */
  plan: CommandBatchPlan
}

/**
 * 交给绑定面板的 first-send recovery：
 * - `replay` 存在：网络/不确定失败——恢复 ordered parts，并保留 exact batch（相同 command id + 原始 expected cursor），以便逐字节 replay；
 * - `replay` 不存在：已知 409——服务器明确拒绝了过期 batch，因此恢复 parts，但下一次提交会重新构建最新 cursor + 最新 command id。
 */
export interface FirstSendRecovery {
  parts: ComposerPart[]
  replay?: CommandBatchReplay
}

/**
 * 在首次消息请求失败时，将原子创建的 Thread + 精确 message batch 传递出去，以便绑定面板恢复 draft 并逐字节 replay。
 */
export class FirstSendMessageError extends Error {
  readonly snapshot: HarnessThreadSnapshotDTO
  readonly plan: CommandBatchPlan
  readonly cause: unknown

  constructor(
    snapshot: HarnessThreadSnapshotDTO,
    plan: CommandBatchPlan,
    cause: unknown,
  ) {
    super(cause instanceof Error ? cause.message : String(cause))
    this.name = 'FirstSendMessageError'
    this.snapshot = snapshot
    this.plan = plan
    this.cause = cause
  }
}

/**
 * 空面板的首次发送顺序：
 * 1) 原子创建携带完整 branch draft（`branchSettings` + `yoloEnabled`）的 Chat Thread，并返回其 snapshot；
 * 2) 使用返回 Thread 的 head cursor，加入仅包含 USER_MESSAGE 的 batch。
 *
 * 消息失败时，保留已创建的 Thread 和精确 batch 以供 replay；branch draft 已在 Thread 创建时持久化。
 */
export async function performBlankPaneFirstSend(options: {
  chatId: string
  parts: ComposerPart[]
  title: string | null
  branchSettings: HarnessBranchSettingsDTO
  yoloEnabled: boolean
  clientCommandId?: string
  createChatThread?: typeof chatService.createChatThread
  enqueueCommands?: typeof harnessService.enqueueCommands
}): Promise<FirstSendResult> {
  const createChatThread = options.createChatThread ?? chatService.createChatThread
  const enqueueCommands = options.enqueueCommands ?? harnessService.enqueueCommands
  const snapshot = await createChatThread(options.chatId, {
    title: options.title,
    branchSettings: options.branchSettings,
    yoloEnabled: options.yoloEnabled,
  })
  const plan = buildFirstSendMessagePlan({
    thread: snapshot.thread,
    parts: options.parts,
    clientCommandId: options.clientCommandId,
  })
  try {
    await enqueueCommands(snapshot.thread.threadId, plan.batch)
  } catch (error) {
    throw new FirstSendMessageError(snapshot, plan, error)
  }
  return {
    threadId: snapshot.thread.threadId,
    snapshot,
    plan,
  }
}
