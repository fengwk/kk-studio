import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentBindingDTO } from '@/shared/api/contracts/ai-environment'
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
 * 面板编辑的完整 branch target：持久化 branch settings 加上 Thread 级别的 YOLO runtime policy。
 * environment 是可空的完整 Environment binding（{name, workspacePath}，null 表示未绑定），
 * 原子地比较/复制——绝不单独透传 name。
 */
export interface BranchDraft {
  environment: EnvironmentBindingDTO | null
  agentName: string
  model: HarnessModelSelectionDTO
  activeTools: string[]
  yoloEnabled: boolean
}

export const LOAD_SKILL_TOOL_NAME = 'load_skill'
export const TASK_TOOL_NAME = 'task'

/** 从 Agent 能力配置派生 branch 的完整 activeTools，包括不可直接选择的内部工具。 */
export function activeToolsFromAgent(agent: AgentDefinitionDTO): string[] {
  const activeTools = new Set(agent.config.tools ?? [])
  if (agent.config.skills?.length) {
    activeTools.add(LOAD_SKILL_TOOL_NAME)
  }
  if (agent.config.subagents?.length) {
    activeTools.add(TASK_TOOL_NAME)
  }
  return [...activeTools]
}

/**
 * 使用 Chat 默认值（agent + yolo）和 catalog，为空面板构建完整的 branch draft：
 * - agent 的 model ref -> provider/model 选择
 * - variant = agent override 或 model 的 defaultVariant（模型 reasoning effort 只来自所选 catalog Variant）
 * - activeTools = agent.config.tools + skills/subagents 对应的内部工具
 * - environment 从调用方传入的 Chat 默认 binding（可 null）开始
 *
 * 当未配置 agent 或无法解析 agent 的 model/variant 时返回 null：
 * 使用空 provider/model/variant 创建 Thread 会被 strict mapper 拒绝，
 * 因此调用方必须显示明确错误或打开 agent picker。
 */
export function materializeBlankBranchDraft(
  agent: AgentDefinitionDTO | undefined,
  yoloEnabled: boolean,
  models: AgentModelView[],
  environment: EnvironmentBindingDTO | null = null,
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
    environment: copyBinding(environment),
    agentName: agent.name,
    model: {
      providerName: model.providerName,
      modelName: model.name,
      variant: variantId,
    },
    activeTools: activeToolsFromAgent(agent),
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
  fallbackEnvironment: EnvironmentBindingDTO | null = null,
): BranchDraft | null {
  // 完整 materialization（没有有效的已有 draft，例如 Chat agent 已过期）必须保留
  // Chat 默认值：不能悄悄丢弃用户设置的 yolo=true 或完整 Environment binding。
  const materialized = materializeBlankBranchDraft(
    agent,
    existing?.yoloEnabled ?? fallbackYoloEnabled ?? false,
    models,
    existing == null ? fallbackEnvironment : existing.environment,
  )
  if (materialized == null) {
    return null
  }
  if (existing == null || existing.model.providerName === '' || existing.model.modelName === '') {
    return materialized
  }
  // Freeze 规则：保留当前 model selection / environment / yolo；采用新的
  // agent 名称及其 active tool 集合。
  return {
    ...materialized,
    environment: copyBinding(existing.environment),
    model: { ...existing.model },
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
    environment: copyBinding(settings.environment),
    agentName: settings.agentName,
    model: {
      providerName: settings.model.providerName,
      modelName: settings.model.modelName,
      variant: settings.model.variant,
    },
    activeTools: settings.activeTools ? [...settings.activeTools] : [],
    yoloEnabled,
  }
}

export function branchDraftsEqual(left: BranchDraft, right: BranchDraft): boolean {
  return sameBinding(left.environment, right.environment)
    && left.agentName === right.agentName
    && left.model.providerName === right.model.providerName
    && left.model.modelName === right.model.modelName
    && left.model.variant === right.model.variant
    && left.yoloEnabled === right.yoloEnabled
    && sameStringList(left.activeTools, right.activeTools)
}

/** 整个 binding 原子比较：null 或 {name, workspacePath} 逐字段相等。 */
export function sameBinding(
  left: EnvironmentBindingDTO | null,
  right: EnvironmentBindingDTO | null,
): boolean {
  if (left === right) {
    return true
  }
  if (left == null || right == null) {
    return false
  }
  return left.name === right.name && left.workspacePath === right.workspacePath
}

/** 整个 binding 原子复制；null 保持 null。 */
export function copyBinding(
  binding: EnvironmentBindingDTO | null,
): EnvironmentBindingDTO | null {
  return binding ? { name: binding.name, workspacePath: binding.workspacePath } : null
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
 * 构建 effective base 与 draft 之间的最小 settings command diff，固定顺序为 SET_ENVIRONMENT/SET_AGENT/SET_MODEL/SET_ACTIVE_TOOLS/SET_YOLO。
 * 每个 command 都通过注入的 id factory 携带自己的稳定 clientCommandId。
 */
export function buildBranchDiffCommands(
  base: BranchDraft,
  draft: BranchDraft,
  createCommandId: () => string,
): HarnessThreadCommandCreateDTO[] {
  const commands: HarnessThreadCommandCreateDTO[] = []
  if (!sameBinding(base.environment, draft.environment)) {
    commands.push({
      type: 'SET_ENVIRONMENT',
      clientCommandId: createCommandId(),
      environment: copyBinding(draft.environment),
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
  let projected: BranchDraft = {
    ...base,
    environment: copyBinding(base.environment),
    activeTools: [...base.activeTools],
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
    case 'SET_ENVIRONMENT': {
      // 整个 binding 原子投影：payload.environment 为 {name, workspacePath} 或 null。
      const environment = payload.environment
      if (environment === null) {
        return { ...base, environment: null }
      }
      if (environment && typeof environment === 'object' && !Array.isArray(environment)) {
        const record = environment as Record<string, unknown>
        if (
          typeof record.name === 'string'
          && record.name.trim() !== ''
          && typeof record.workspacePath === 'string'
          && record.workspacePath.trim() !== ''
        ) {
          return {
            ...base,
            environment: { name: record.name, workspacePath: record.workspacePath },
          }
        }
      }
      // 非法/不完整 binding：绝不把 name 单独透传；保持 base 不变。
      return base
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
