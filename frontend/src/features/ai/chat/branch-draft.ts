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
 * workspacePath 是可空的 canonical workspace path（null 表示未指定工作目录）。
 */
export interface BranchDraft {
  workspacePath: string | null
  agentName: string
  model: HarnessModelSelectionDTO
  yoloEnabled: boolean
}

/**
 * 使用 Chat 默认值（agent + yolo）和 catalog，为空面板构建完整的 branch draft：
 * - agent 的 model ref -> provider/model 选择
 * - variant = agent override 或 model 的 defaultVariant
 * - workspacePath 从调用方传入的 Chat 默认 workspacePath（可 null）开始；如果 agent 未绑定 environmentId，则强制为 null。
 */
export function materializeBlankBranchDraft(
  agent: AgentDefinitionDTO | undefined,
  yoloEnabled: boolean,
  models: AgentModelView[],
  workspacePath: string | null = null,
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
  const effectiveWorkspacePath = agent.environmentId ? (workspacePath ?? null) : null
  return {
    workspacePath: effectiveWorkspacePath,
    agentName: agent.name,
    model: {
      providerName: model.providerName,
      modelName: model.name,
      variant: variantId,
    },
    yoloEnabled,
  }
}

/**
 * 根据明确选中的 Agent + catalog 构建完整 draft。
 *
 * 切换 Agent 规则：
 * - 如果新 Agent 没有绑定 environmentId，或者新旧 Agent 的 environmentId 不一致，则 workspacePath 必须清空为 null，
 *   防止旧环境的 workspacePath 被静默带到不同环境；
 * - 只有新旧 Agent 属于同一 environmentId 时，才保留已有 workspacePath。
 */
export function materializeAgentBranchDraft(
  agent: AgentDefinitionDTO,
  models: AgentModelView[],
  existing: BranchDraft | null,
  fallbackYoloEnabled?: boolean,
  fallbackWorkspacePath: string | null = null,
  previousAgent?: AgentDefinitionDTO | null,
): BranchDraft | null {
  const isSameEnvironment =
    Boolean(agent.environmentId)
    && Boolean(previousAgent?.environmentId)
    && previousAgent?.environmentId === agent.environmentId

  const targetWorkspacePath = isSameEnvironment
    ? (existing?.workspacePath ?? fallbackWorkspacePath ?? null)
    : null

  const materialized = materializeBlankBranchDraft(
    agent,
    existing?.yoloEnabled ?? fallbackYoloEnabled ?? false,
    models,
    targetWorkspacePath,
  )
  if (materialized == null) {
    return null
  }
  if (existing == null || existing.model.providerName === '' || existing.model.modelName === '') {
    return materialized
  }
  return {
    ...materialized,
    workspacePath: targetWorkspacePath,
    model: { ...existing.model },
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
    workspacePath: settings.workspacePath ?? null,
    agentName: settings.agentName,
    model: {
      providerName: settings.model.providerName,
      modelName: settings.model.modelName,
      variant: settings.model.variant,
    },
    yoloEnabled,
  }
}

export function branchDraftsEqual(left: BranchDraft, right: BranchDraft): boolean {
  return left.workspacePath === right.workspacePath
    && left.agentName === right.agentName
    && left.model.providerName === right.model.providerName
    && left.model.modelName === right.model.modelName
    && left.model.variant === right.model.variant
    && left.yoloEnabled === right.yoloEnabled
}

/**
 * 构建 effective base 与 draft 之间的最小 settings command diff，固定顺序为 SET_ENVIRONMENT/SET_AGENT/SET_MODEL。
 */
export function buildBranchDiffCommands(
  base: BranchDraft,
  draft: BranchDraft,
  createCommandId: () => string,
): HarnessCommandCreateDTO[] {
  const commands: HarnessCommandCreateDTO[] = []
  if (base.workspacePath !== draft.workspacePath) {
    commands.push({
      type: 'SET_ENVIRONMENT',
      idempotencyKey: createCommandId(),
      workspacePath: draft.workspacePath,
    })
  }
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
    case 'SET_ENVIRONMENT': {
      if (payload.workspacePath === null) {
        return { ...base, workspacePath: null }
      }
      if (typeof payload.workspacePath === 'string') {
        const trimmed = payload.workspacePath.trim()
        return { ...base, workspacePath: trimmed !== '' ? trimmed : null }
      }
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
