import { copyExecutionSettings, type HarnessCommandCreateDTO } from '@/shared/api/contracts/ai-runtime'
import { ApiError } from '@/shared/api/client'
import type { BranchDraft } from '@/features/thread/thread-settings'
import type { HarnessThreadCommandBatchDTO } from '@/shared/api/contracts/ai-runtime'
import type { HarnessThreadDTO } from '@/shared/api/generated/contracts'

/**
 * 单条纯 USER_MESSAGE 批次不依赖 Thread cursor：服务端按当前邮箱尾部入队。任何 SET_* 或
 * 多条命令的批次仍需要精确 cursor。
 */
export function isCursorFreeThreadBatch(commands: HarnessCommandCreateDTO[]): boolean {
  return commands.length === 1 && commands[0]?.type === 'USER_MESSAGE'
}

/**
 * 既有 Thread 的通用写请求：服务端从 path 解析 Session，客户端只提交命令与依赖当前视图时的
 * 精确 CAS 游标。纯消息不冻结 cursor；设置批次必须来自 freshly 读取的权威 snapshot。
 */
export function buildThreadCommandBatchRequest(
  thread: HarnessThreadDTO,
  commands: HarnessCommandCreateDTO[],
): HarnessThreadCommandBatchDTO {
  if (isCursorFreeThreadBatch(commands)) {
    return { commands }
  }
  return {
    expectedHeadEntryId: thread.headEntryId,
    expectedNextCommandSequence: thread.nextCommandSequence,
    commands,
  }
}

export function copyBranchDraft(draft: BranchDraft): BranchDraft {
  return {
    ...draft,
    execution: {
      driver: draft.execution.driver,
      settings: copyExecutionSettings(draft.execution.settings),
    },
  }
}

export function isDefiniteAcceptanceFailure(error: unknown): boolean {
  if (error instanceof ApiError) {
    const status = error.status
    if (status === 408 || status === 429) {
      return false
    }
    return typeof status === 'number' && status >= 400 && status < 500
  }
  return false
}
