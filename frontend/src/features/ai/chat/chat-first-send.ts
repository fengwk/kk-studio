import type {
  HarnessBranchSettingsDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { CommandBatchReplay } from '@/features/ai/runtime'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import {
  buildFirstSendMessagePlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'

export interface FirstSendResult {
  threadId: string
  snapshot: HarnessThreadSnapshotDTO
  /** The exact USER_MESSAGE-only batch sent after creation (replay identity). */
  plan: CommandBatchPlan
}

/**
 * First-send recovery handed to the bound pane:
 * - `replay` present: network/uncertain failure — restore the text AND keep the exact batch
 *   (same command ids + original expected cursors) for byte-for-byte replay;
 * - `replay` absent: known 409 — the server explicitly rejected the stale batch, so the text
 *   is restored but the NEXT submit rebuilds fresh cursors + fresh command ids.
 */
export interface FirstSendRecovery {
  content: string
  replay?: CommandBatchReplay
}

/**
 * Carries the atomically created Thread + exact message batch across a failed first-message
 * request so the bound pane can restore the draft and replay byte-for-byte.
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
 * Blank pane first send order:
 * 1) create a Chat Thread atomically carrying the full branch draft (`branchSettings` +
 *    `yoloEnabled`) and return its snapshot;
 * 2) enqueue a USER_MESSAGE-only batch against the returned Thread's head cursors.
 *
 * On message failure the created Thread and the exact batch are preserved for replay; the
 * branch draft itself is already durable in the Thread creation.
 */
export async function performBlankPaneFirstSend(options: {
  chatId: string
  content: string
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
    content: options.content,
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
