import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type {
  HarnessBranchSettingsDTO,
  HarnessModelSelectionDTO,
  HarnessThreadCommandCreateDTO,
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'
import { modelRef, type AgentModelView } from '@/features/ai/catalog'
import { parsePayload } from '@/features/ai/runtime/payload-json'

/**
 * 面板编辑的完整 branch target：持久化 branch settings 加上 Thread 级别的 YOLO runtime policy。environmentId 是规范化的小写 UUID 路由标识。
 */
export interface BranchDraft {
  environmentId: string | null
  agentName: string
  model: HarnessModelSelectionDTO
  thinkingLevel: string
  activeTools: string[]
  yoloEnabled: boolean
}

export const DEFAULT_THINKING_LEVEL = 'off'

/**
 * 使用 Chat 默认值（agent + yolo）和 catalog，为空面板构建完整的 branch draft：
 * - agent 的 model ref -> provider/model 选择
 * - variant = agent override 或 model 的 defaultVariant
 * - thinkingLevel = 该 variant 的 reasoningEffort 或 `off`
 * - activeTools = agent.config.tools
 * - environmentId 从 null 开始
 *
 * 当未配置 agent 或无法解析 agent 的 model/variant 时返回 null：
 * 使用空 provider/model/variant 创建 Thread 会被 strict mapper 拒绝，
 * 因此调用方必须显示明确错误或打开 agent picker。
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
    environmentId: null,
    agentName: agent.name,
    model: {
      providerName: model.providerName,
      modelName: model.name,
      variant: variantId,
    },
    thinkingLevel: variant.reasoningEffort || DEFAULT_THINKING_LEVEL,
    activeTools: agent.config.tools ? [...agent.config.tools] : [],
    yoloEnabled,
  }
}

/**
 * 根据明确选中的 Agent + catalog 构建完整 draft（当前 draft 尚无有效 model selection 时使用，例如 Chat agent 已过期）。否则复用已有 frozen draft 的持久化值。
 */
export function materializeAgentBranchDraft(
  agent: AgentDefinitionDTO,
  models: AgentModelView[],
  existing: BranchDraft | null,
  fallbackYoloEnabled?: boolean,
): BranchDraft | null {
  // 完整 materialization（没有有效的已有 draft，例如 Chat agent 已过期）必须保留
  // Chat 默认值：`?? false` 会悄悄丢弃用户设置的 yolo=true 偏好。
  const materialized = materializeBlankBranchDraft(
    agent,
    existing?.yoloEnabled ?? fallbackYoloEnabled ?? false,
    models,
  )
  if (materialized == null) {
    return null
  }
  if (existing == null || existing.model.providerName === '' || existing.model.modelName === '') {
    return materialized
  }
  // Freeze 规则：保留当前 model selection / thinking / environment / yolo；采用新的
  // agent 名称及其 active tool 集合。
  return {
    ...materialized,
    environmentId: existing.environmentId,
    model: { ...existing.model },
    thinkingLevel: existing.thinkingLevel,
    yoloEnabled: existing.yoloEnabled,
  }
}

/** 从持久化的 Thread snapshot 初始化绑定面板 draft（绝不使用 Chat 默认值）。 */
export function branchDraftFromThread(thread: HarnessThreadDTO): BranchDraft {
  return branchDraftFromBranchSettings(thread.branchSettings, thread.yoloEnabled)
}

export function branchDraftFromBranchSettings(
  settings: HarnessBranchSettingsDTO,
  yoloEnabled: boolean,
): BranchDraft {
  return {
    environmentId: settings.environmentId ?? null,
    agentName: settings.agentName,
    model: {
      providerName: settings.model.providerName,
      modelName: settings.model.modelName,
      variant: settings.model.variant,
    },
    thinkingLevel: settings.thinkingLevel,
    activeTools: settings.activeTools ? [...settings.activeTools] : [],
    yoloEnabled,
  }
}

export function branchDraftsEqual(left: BranchDraft, right: BranchDraft): boolean {
  return left.environmentId === right.environmentId
    && left.agentName === right.agentName
    && left.model.providerName === right.model.providerName
    && left.model.modelName === right.model.modelName
    && left.model.variant === right.model.variant
    && left.thinkingLevel === right.thinkingLevel
    && left.yoloEnabled === right.yoloEnabled
    && sameStringList(left.activeTools, right.activeTools)
}

function sameStringList(left: string[], right: string[]): boolean {
  if (left.length !== right.length) {
    return false
  }
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) {
      return false
    }
  }
  return true
}

/**
 * 构建 effective base 与 draft 之间的最小 settings command diff，固定顺序为 SET_ENVIRONMENT/SET_AGENT/SET_MODEL/SET_THINKING_LEVEL/SET_ACTIVE_TOOLS/SET_YOLO。
 * 每个 command 都通过注入的 id factory 携带自己的稳定 clientCommandId。
 */
export function buildBranchDiffCommands(
  base: BranchDraft,
  draft: BranchDraft,
  createCommandId: () => string,
): HarnessThreadCommandCreateDTO[] {
  const commands: HarnessThreadCommandCreateDTO[] = []
  if (base.environmentId !== draft.environmentId) {
    commands.push({
      type: 'SET_ENVIRONMENT',
      clientCommandId: createCommandId(),
      environmentId: draft.environmentId,
    })
  }
  if (base.agentName !== draft.agentName) {
    commands.push({
      type: 'SET_AGENT',
      clientCommandId: createCommandId(),
      agentName: draft.agentName,
    })
  }
  if (!sameModelSelection(base.model, draft.model)) {
    commands.push({
      type: 'SET_MODEL',
      clientCommandId: createCommandId(),
      model: { ...draft.model },
    })
  }
  if (base.thinkingLevel !== draft.thinkingLevel) {
    commands.push({
      type: 'SET_THINKING_LEVEL',
      clientCommandId: createCommandId(),
      thinkingLevel: draft.thinkingLevel,
    })
  }
  if (!sameStringList(base.activeTools, draft.activeTools)) {
    commands.push({
      type: 'SET_ACTIVE_TOOLS',
      clientCommandId: createCommandId(),
      activeTools: [...draft.activeTools],
    })
  }
  if (base.yoloEnabled !== draft.yoloEnabled) {
    commands.push({
      type: 'SET_YOLO',
      clientCommandId: createCommandId(),
      yoloEnabled: draft.yoloEnabled,
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
 * 用于避免重新发送已在处理中、尚未完成的 settings。
 */
export function projectPendingTarget(
  base: BranchDraft,
  queuedCommands: HarnessThreadCommandDTO[],
): BranchDraft {
  let projected: BranchDraft = { ...base, activeTools: [...base.activeTools] }
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
    case 'SET_ENVIRONMENT': {
      const environmentId = typeof payload.environmentId === 'string' && payload.environmentId
        ? payload.environmentId
        : null
      return { ...base, environmentId }
    }
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
    case 'SET_THINKING_LEVEL': {
      const thinkingLevel = typeof payload.thinkingLevel === 'string'
        ? payload.thinkingLevel
        : base.thinkingLevel
      return { ...base, thinkingLevel }
    }
    case 'SET_ACTIVE_TOOLS': {
      const activeTools = Array.isArray(payload.activeTools)
        ? payload.activeTools.filter((item): item is string => typeof item === 'string')
        : base.activeTools
      return { ...base, activeTools: [...activeTools] }
    }
    case 'SET_YOLO': {
      const yoloEnabled = typeof payload.yoloEnabled === 'boolean'
        ? payload.yoloEnabled
        : base.yoloEnabled
      return { ...base, yoloEnabled }
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
