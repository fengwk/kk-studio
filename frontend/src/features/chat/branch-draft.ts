import type { HarnessCommandCreateDTO } from '@/shared/api/contracts/ai-runtime'
import type { AgentModelView } from '@/features/catalog'
import { parsePayload } from '@/features/chat/runtime/payload-json'
import type {
  AgentDefinitionDTO,
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
} from '@/shared/api/generated/contracts'

/**
 * 面板编辑的完整 branch target：持久化 branch settings 加上 Thread 级别的 YOLO runtime policy。
 */
export interface BranchDraft {
  agentName: string
  model: HarnessModelSelectionDTO
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
  if (!agent || !agent.model) {
    return null
  }
  const model = models.find((item) => item.name === agent.model)
  if (!model) {
    return null
  }
  if (model.kind === 'GROUP') {
    // 组没有组级 Variant；成员的 Variant 已固化在组配置中。
    return {
      agentName: agent.name,
      model: { modelName: model.name, variant: null },
      environmentName: environmentName ?? null,
      yoloEnabled,
    }
  }
  const variantId = agent.variant || model.config?.defaultVariant || ''
  const variant = model.config?.variants.find((item) => item.id === variantId)
  if (!variantId || !variant) {
    return null
  }
  return {
    agentName: agent.name,
    model: { modelName: model.name, variant: variantId },
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
    model: {
      modelName: settings.model.modelName,
      variant: settings.model.variant ?? null,
    },
    environmentName: settings.environmentName,
    yoloEnabled,
  }
}

export function branchDraftsEqual(left: BranchDraft, right: BranchDraft): boolean {
  return left.agentName === right.agentName
    && left.model.modelName === right.model.modelName
    && (left.model.variant ?? null) === (right.model.variant ?? null)
    && left.environmentName === right.environmentName
    && left.yoloEnabled === right.yoloEnabled
}

/**
 * 构建 effective base 与 draft 之间的最小 settings command diff，固定顺序为 SET_AGENT/SET_MODEL/SET_ENVIRONMENT。
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
  if (!sameModelSelection(base.model, draft.model)) {
    commands.push({
      type: 'SET_MODEL',
      idempotencyKey: createCommandId(),
      model: { ...draft.model },
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

function sameModelSelection(left: HarnessModelSelectionDTO, right: HarnessModelSelectionDTO): boolean {
  return left.modelName === right.modelName
    && (left.variant ?? null) === (right.variant ?? null)
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
    case 'SET_MODEL': {
      const model = payload.model
      if (model && typeof model === 'object' && !Array.isArray(model)) {
        const record = model as Record<string, unknown>
        return {
          ...base,
          model: {
            modelName: typeof record.modelName === 'string' ? record.modelName : base.model.modelName,
            variant: typeof record.variant === 'string'
              ? record.variant
              : record.variant === null
                ? null
                : (base.model.variant ?? null),
          },
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
