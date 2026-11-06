import {
  type HarnessCommandCreateDTO,
  createBuiltinExecution,
  getBuiltinModelSelection,
  isSameExecution,
} from '@/shared/api/contracts/ai-runtime'
import type { AgentModelView } from '@/features/catalog'
import { parsePayload } from '@/features/thread/runtime/payload-json'
import type {
  AgentDefinitionDTO,
  HarnessBranchSettingsDTO,
  HarnessExecutionSelectionDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
} from '@/shared/api/generated/contracts'

/**
 * 面板编辑的完整 branch target：持久化 branch settings 加上 Thread 级别的 YOLO runtime policy。
 */
export interface BranchDraft {
  agentName: string
  execution: HarnessExecutionSelectionDTO
  environmentName: string | null
  yoloEnabled: boolean
}

/**
 * 使用 Chat 默认值（agent + yolo）和 catalog，为空面板构建完整的 branch draft：
 * - agent 的全局模型名 -> 模型选择（组没有组级 Variant）
 * - variant = agent override 或 model 的 defaultVariant
 */
export function materializeBlankBranchDraft(
  agent: AgentDefinitionDTO | undefined,
  yoloEnabled: boolean,
  models: AgentModelView[],
  environmentName?: string | null,
): BranchDraft | null {
  if (!agent?.execution) {
    return null
  }
  if (agent.execution.driver !== 'builtin') {
    return { agentName: agent.name, execution: agent.execution, environmentName: environmentName ?? null, yoloEnabled }
  }
  const selection = getBuiltinModelSelection(agent.execution)
  const model = models.find((item) => item.name === selection?.modelName)
  if (!model) {
    return null
  }
  if (model.type === 'GROUP') {
    // 组没有组级 Variant；成员的 Variant 已固化在组配置中。
    return {
      agentName: agent.name,
      execution: createBuiltinExecution(model.name, null),
      environmentName: environmentName ?? null,
      yoloEnabled,
    }
  }
  const variantId = selection?.variant || model.config?.defaultVariant || ''
  const variant = model.config?.variants.find((item) => item.id === variantId)
  if (!variantId || !variant) {
    return null
  }
  return {
    agentName: agent.name,
    execution: createBuiltinExecution(model.name, variantId),
    environmentName: environmentName ?? null,
    yoloEnabled,
  }
}

/**
 * 根据明确选中的 Agent + catalog 构建完整 draft。
 * 始终采用目标 Agent 所选取的 model 与 variant，同时保留已有 draft 的 environmentName 与 yoloEnabled。
 */
export function materializeAgentBranchDraft(
  agent: AgentDefinitionDTO,
  models: AgentModelView[],
  existing: BranchDraft | null,
  fallbackYoloEnabled?: boolean,
  fallbackEnvironmentName?: string | null,
): BranchDraft | null {
  const materialized = materializeBlankBranchDraft(
    agent,
    existing?.yoloEnabled ?? fallbackYoloEnabled ?? false,
    models,
    existing != null ? existing.environmentName : (fallbackEnvironmentName ?? null),
  )
  if (materialized == null) {
    return null
  }
  if (existing == null) {
    return materialized
  }
  return {
    ...materialized,
    environmentName: existing.environmentName,
    yoloEnabled: existing.yoloEnabled,
  }
}

/** 从持久化的 Thread snapshot 初始化绑定面板 draft；子代理的 FOLLOW 不代表本地开关。 */
export function branchDraftFromThread(thread: HarnessThreadDTO): BranchDraft {
  return branchDraftFromBranchSettings(thread.branchSettings, thread.yoloEnabled)
}

export function branchDraftFromBranchSettings(
  settings: HarnessBranchSettingsDTO,
  yoloEnabled: boolean,
): BranchDraft {
  return {
    agentName: settings.agentName,
    execution: settings.execution,
    environmentName: settings.environmentName,
    yoloEnabled,
  }
}

export function branchDraftsEqual(left: BranchDraft, right: BranchDraft): boolean {
  return left.agentName === right.agentName
    && isSameExecution(left.execution, right.execution)
    && left.environmentName === right.environmentName
    && left.yoloEnabled === right.yoloEnabled
}

/**
 * 构建 effective base 与 draft 之间的最小 settings command diff，固定顺序为 SET_AGENT/SET_EXECUTION/SET_ENVIRONMENT。
 */
export function buildBranchDiffCommands(
  base: BranchDraft,
  draft: BranchDraft,
  createCommandId: () => string,
): HarnessCommandCreateDTO[] {
  const commands: HarnessCommandCreateDTO[] = []
  if (base.agentName !== draft.agentName) {
    commands.push({
      type: 'SET_AGENT',
      idempotencyKey: createCommandId(),
      agentName: draft.agentName,
    })
  }
  if (!isSameExecution(base.execution, draft.execution)) {
    commands.push({
      type: 'SET_EXECUTION',
      idempotencyKey: createCommandId(),
      execution: {
        driver: draft.execution.driver,
        settings: { ...draft.execution.settings },
      },
    })
  }
  if (base.environmentName !== draft.environmentName) {
    commands.push({
      type: 'SET_ENVIRONMENT',
      idempotencyKey: createCommandId(),
      environmentName: draft.environmentName,
    })
  }
  return commands
}

/**
 * 通过按 sequence 顺序将 QUEUED SET_* command payload 应用到持久化 base，推算 pending branch target。
 */
export function projectPendingTarget(
  base: BranchDraft,
  queuedCommands: HarnessThreadCommandDTO[],
): BranchDraft {
  let projected: BranchDraft = {
    ...base,
  }
  const ordered = [...queuedCommands]
    .filter((command) => command.state === 'QUEUED' && command.type.startsWith('SET_'))
    .sort((left, right) => compareDecimalStrings(left.sequence, right.sequence))
  for (const command of ordered) {
    projected = applySettingCommand(projected, command)
  }
  return projected
}

function applySettingCommand(base: BranchDraft, command: HarnessThreadCommandDTO): BranchDraft {
  const payload = parsePayload(command.payloadJson)
  switch (command.type) {
    case 'SET_AGENT': {
      const agentName = typeof payload.agentName === 'string' ? payload.agentName : base.agentName
      return { ...base, agentName }
    }
    case 'SET_EXECUTION': {
      const execution = payload.execution
      if (execution && typeof execution === 'object' && !Array.isArray(execution)) {
        const record = execution as Record<string, unknown>
        const driver = typeof record.driver === 'string' ? record.driver : base.execution.driver
        if (driver === 'builtin') {
          const baseModel = getBuiltinModelSelection(base.execution)
          const baseModelName = baseModel?.modelName ?? ''
          const baseVariant = baseModel?.variant ?? null
          const settings = record.settings
          if (settings && typeof settings === 'object' && !Array.isArray(settings)) {
            const s = settings as Record<string, unknown>
            const modelName = typeof s.modelName === 'string' ? s.modelName : baseModelName
            const variant = typeof s.variant === 'string'
              ? s.variant
              : s.variant === null
                ? null
                : baseVariant
            return {
              ...base,
              execution: createBuiltinExecution(modelName, variant),
            }
          }
        } else {
          return {
            ...base,
            execution: {
              driver,
              settings: (record.settings && typeof record.settings === 'object' && !Array.isArray(record.settings))
                ? (record.settings as Record<string, unknown>)
                : base.execution.settings,
            },
          }
        }
      }
      return base
    }
    case 'SET_ENVIRONMENT': {
      if (Object.hasOwn(payload, 'environmentName')) {
        if (typeof payload.environmentName === 'string') {
          return { ...base, environmentName: payload.environmentName }
        }
        if (payload.environmentName === null) {
          return { ...base, environmentName: null }
        }
      }
      return base
    }
    default:
      return base
  }
}

/** 用于 command sequence 排序的十进制字符串比较。 */
export function compareDecimalStrings(left: string, right: string): number {
  try {
    const l = BigInt(left)
    const r = BigInt(right)
    return l < r ? -1 : l > r ? 1 : 0
  } catch {
    return 0
  }
}
