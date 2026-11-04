import type {
  HarnessBranchSettingsDTO,
  HarnessExecutionSelectionDTO,
  HarnessModelSelectionDTO,
} from '@/shared/api/generated/contracts'

export const BUILTIN_DRIVER = 'builtin'

export interface BuiltinExecutionSettings {
  modelName: string
  variant: string | null
}

export interface BuiltinExecutionSelection {
  driver: 'builtin'
  settings: BuiltinExecutionSettings
}

export function createBuiltinExecution(
  model: { modelName: string; variant?: string | null } | string,
  variant?: string | null,
): HarnessExecutionSelectionDTO {
  if (typeof model === 'string') {
    return {
      driver: BUILTIN_DRIVER,
      settings: {
        modelName: model,
        variant: variant ?? null,
      },
    }
  }
  return {
    driver: BUILTIN_DRIVER,
    settings: {
      modelName: model.modelName,
      variant: model.variant ?? null,
    },
  }
}

export function getBuiltinModelSelection(
  execution: unknown,
): HarnessModelSelectionDTO | null {
  if (execution == null || typeof execution !== 'object' || Array.isArray(execution)) {
    return null
  }
  const candidate = execution as { driver?: unknown; settings?: unknown }
  if (candidate.driver !== BUILTIN_DRIVER) {
    return null
  }
  const settings = candidate.settings
  if (settings == null || typeof settings !== 'object' || Array.isArray(settings)) {
    return null
  }
  const s = settings as { modelName?: unknown; variant?: unknown }
  if (typeof s.modelName !== 'string' || !s.modelName.trim()) {
    return null
  }
  const modelVariant = typeof s.variant === 'string' ? s.variant : null
  return {
    modelName: s.modelName,
    variant: modelVariant,
  }
}

export function isSameExecution(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a == null || b == null || typeof a !== 'object' || typeof b !== 'object') return false
  const execA = a as { driver?: unknown; settings?: unknown }
  const execB = b as { driver?: unknown; settings?: unknown }
  if (execA.driver !== execB.driver) return false
  if (execA.driver === BUILTIN_DRIVER) {
    const modelA = getBuiltinModelSelection(a)
    const modelB = getBuiltinModelSelection(b)
    if (modelA == null || modelB == null) return false
    return modelA.modelName === modelB.modelName && (modelA.variant ?? null) === (modelB.variant ?? null)
  }
  // 服务端按键序规范化 settings，同一选择的 JSON 文本相同。
  return JSON.stringify(execA.settings) === JSON.stringify(execB.settings)
}

export type EntryType =
  | 'ROOT'
  | 'TURN_START'
  | 'MESSAGE'
  | 'CUSTOM'
  | 'CUSTOM_MESSAGE'
  | 'NOTIFICATION'
  | 'FORK'
  | 'MODEL_ATTEMPT_FAILURE'
  | 'ASSISTANT_ERROR'
  | 'ASSISTANT_ABORTED'
  | 'COMPACTION'
  | 'TURN_END'
  | 'SETTINGS'

/**
 * 类型化的 Thread mailbox command 请求。
 *
 * USER_MESSAGE 只接受一个非空、有序的 contents 列表（TEXT/ATTACHMENT/RESOURCE），不提供
 * 任何文本 shorthand。持久 Entry/投影仍可包含 CUSTOM_MESSAGE，但它不是创建命令。
 * idempotencyKey 是稳定的幂等键。
 */
/** 图片输入档位；Wire 与 durable 均采用 720P / 1080P / ORIGINAL。 */
export type ImageInputTier = '720P' | '1080P' | 'ORIGINAL'

export type HarnessUserMessageContentDTO =
  | { type: 'TEXT'; text: string }
  /**
   * 共享存储 READY 上传的引用。uploadId 是完成后的持久句柄；后端在入队事务内
   * 将其原子物化为 durable RESOURCE。
   */
  | { type: 'ATTACHMENT'; uploadId: string; imageTier?: ImageInputTier }
  /** 当前 Session 已拥有的 durable blob ref；用于 Stop 后 Resource pill 重提。 */
  | { type: 'RESOURCE'; blobId: string; name: string; preview?: string | null; imageTier?: ImageInputTier }

type HarnessUserMessageCommandDTO = {
  type: 'USER_MESSAGE'
  idempotencyKey: string
  contents: [HarnessUserMessageContentDTO, ...HarnessUserMessageContentDTO[]]
}

export type HarnessCommandCreateDTO =
  | HarnessUserMessageCommandDTO
  | { type: 'GOAL'; idempotencyKey: string; text: string | null }
  | { type: 'SET_AGENT'; idempotencyKey: string; agentName: string }
  | { type: 'SET_EXECUTION'; idempotencyKey: string; execution: HarnessExecutionSelectionDTO }
  | { type: 'SET_ENVIRONMENT'; idempotencyKey: string; environmentName: string | null }

export type AgentRuntimeOwnerDTO = { kind: string; id: string }

export interface NewSessionCommandTargetDTO {
  type: 'NEW_SESSION'
  sessionId: string
  threadId: string
  rootSettings: HarnessBranchSettingsDTO
  yoloEnabled: boolean
}

export interface NewThreadCommandTargetDTO {
  type: 'NEW_THREAD'
  sessionId: string
  startEntryId: string
  threadId: string
  threadName: string
  yoloEnabled: boolean
}

/**
 * 会话 fork 创建 target：从 sourceThread 的 startEntryId 切点复制有效上下文到新 Session
 * 与新 Thread。settings 从源切点 branch 推导，因此不携带 rootSettings/threadName；
 * commands 与产品 owner 语义同 NEW_SESSION / NEW_THREAD。
 */
export interface NewForkedSessionCommandTargetDTO {
  type: 'NEW_FORKED_SESSION'
  sourceThreadId: string
  startEntryId: string
  sessionId: string
  threadId: string
  yoloEnabled: boolean
}

/** 创建型命令批次 target：NEW_SESSION / NEW_THREAD / NEW_FORKED_SESSION，都需要产品 owner。 */
export type AgentCommandTargetDTO =
  | NewSessionCommandTargetDTO
  | NewThreadCommandTargetDTO
  | NewForkedSessionCommandTargetDTO

/** 创建型命令批次：owner 为不透明身份，target 与 commands 使用判别联合。 */
export interface HarnessCommandBatchDTO {
  owner: AgentRuntimeOwnerDTO
  target: AgentCommandTargetDTO
  commands: HarnessCommandCreateDTO[]
}

/**
 * 既有 Thread 的命令批次：有序命令，以及仅在批次依赖当前视图时携带的精确 CAS 游标。
 */
export interface HarnessThreadCommandBatchDTO {
  expectedHeadEntryId?: string | null
  expectedNextCommandSequence?: string | null
  commands: HarnessCommandCreateDTO[]
}
