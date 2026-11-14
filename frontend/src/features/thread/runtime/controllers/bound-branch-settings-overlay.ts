import type { HarnessThreadCommandDTO, HarnessThreadDTO } from '@/shared/api/generated/contracts'
import { isThreadActiveStatus } from '@/features/thread/runtime/thread-panel/thread-status-format'

/** 本地即时提交的 SET_* 命令：在快照回读前补进 projection，携带回读水位与幂等键。 */
export interface SettingsOverlayEntry {
  threadId: string
  command: HarnessThreadCommandDTO
  watermarkVersion: string
}

/** 仅 STALE_COMMAND_CURSOR 允许借既有冲突回读机制重建一次 cursor/diff，不另造任意重试。 */
export const MAX_STALE_SETTINGS_CURSOR_RETRIES = 2

/** 活动与执行控制正交；等待异步结果、退出待确认等其它非 IDLE 状态也仍占用当前执行。 */
export function isBusyThread(thread: HarnessThreadDTO): boolean {
  return isThreadActiveStatus(thread.status)
}

/**
 * 精确比较非负十进制 version 字符串（后端经 Long.toString 生成，可能远超
 * Number.MAX_SAFE_INTEGER）。用 BigInt 避免 double 精度丢失导致回退误判。
 * 返回 -1 / 0 / 1。
 */
export function compareDecimalVersions(a: string, b: string): number {
  const bigA = BigInt(a)
  const bigB = BigInt(b)
  if (bigA < bigB) {
    return -1
  }
  if (bigA > bigB) {
    return 1
  }
  return 0
}

/** overlay 只在快照尚未达到其水位时有效：达到后服务器排队命令即权威事实。 */
export function activeSettingsOverlay(
  entries: readonly SettingsOverlayEntry[],
  threadId: string,
  threadVersion: string | undefined,
): SettingsOverlayEntry[] {
  return entries.filter((entry) =>
    entry.threadId === threadId
    && threadVersion != null
    && compareDecimalVersions(threadVersion, entry.watermarkVersion) < 0)
}

/**
 * 把本地 overlay 命令并入服务器排队命令供 projection 使用：服务器已回读的命令以服务器
 * 事实为准（按 idempotencyKey 去重），overlay 只补尚未回读的差集。
 */
export function mergeQueuedCommands(
  serverQueued: readonly HarnessThreadCommandDTO[],
  overlay: readonly SettingsOverlayEntry[],
  threadId: string,
): HarnessThreadCommandDTO[] {
  if (overlay.length === 0) {
    return [...serverQueued]
  }
  const observed = new Set(serverQueued.map((command) => command.idempotencyKey))
  const extra = overlay
    .filter((entry) => entry.threadId === threadId)
    .map((entry) => entry.command)
    .filter((command) => !observed.has(command.idempotencyKey))
  return extra.length === 0 ? [...serverQueued] : [...serverQueued, ...extra]
}
