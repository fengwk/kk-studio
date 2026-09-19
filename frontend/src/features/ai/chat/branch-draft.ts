import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessCommandCreateDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'
import { modelRef, type AgentModelView } from '@/features/ai/catalog'
import { parsePayload } from '@/features/ai/runtime/payload-json'

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
 * - agent 的 model ref -> provider/model 选择
 * - variant = agent override 或 model 的 defaultVariant
 */
export function materializeBlankBranchDraft(
  agent: AgentDefinitionDTO | undefined,
  yoloEnabled: boolean,
  models: AgentModelView[],
): BranchDraft | null {
  if (!agent || !agent.model) {
    return null
  }
  const model = models.find((item) => modelRef(item) === agent.model)
  if (!model) {
    return null
  }
  const variantId = agent.variant || model.config.defaultVariant || ''
  const variant = model.config.variants.find((item) => item.id === variantId)
  if (!variantId || !variant) {
    return null
  }
  return {
    agentName: agent.name,
    model: {
      providerName: model.providerName,
      modelName: model.name,
      variant: variantId,
    },
    environmentName: null,
    yoloEnabled,
  }
}

/**
 * 根据明确选中的 Agent + catalog 构建完整 draft。
 */
export function materializeAgentBranchDraft(
  agent: AgentDefinitionDTO,
  models: AgentModelView[],
  existing: BranchDraft | null,
  fallbackYoloEnabled?: boolean,
): BranchDraft | null {
  const materialized = materializeBlankBranchDraft(
    agent,
    existing?.yoloEnabled ?? fallbackYoloEnabled ?? false,
    models,
  )
  if (materialized == null) {
    return null
  }
  if (existing == null) {
    return materialized
  }
  const environmentName = existing.environmentName
  if (existing.model.providerName === '' || existing.model.modelName === '') {
    return {
      ...materialized,
      environmentName,
    }
  }
  return {
    ...materialized,
    model: { ...existing.model },
    environmentName,
    yoloEnabled: existing.yoloEnabled,
  }
}

/** 从持久化的 Thread snapshot 初始化绑定面板 draft。 */
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
      providerName: settings.model.providerName,
      modelName: settings.model.modelName,
      variant: settings.model.variant,
    },
    environmentName: settings.environmentName,
    yoloEnabled,
  }
}

export function branchDraftsEqual(left: BranchDraft, right: BranchDraft): boolean {
  return left.agentName === right.agentName
    && left.model.providerName === right.model.providerName
    && left.model.modelName === right.model.modelName
    && left.model.variant === right.model.variant
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
  return left.providerName === right.providerName
    && left.modelName === right.modelName
    && left.variant === right.variant
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
            providerName: typeof record.providerName === 'string' ? record.providerName : base.model.providerName,
            modelName: typeof record.modelName === 'string' ? record.modelName : base.model.modelName,
            variant: typeof record.variant === 'string' ? record.variant : base.model.variant,
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
