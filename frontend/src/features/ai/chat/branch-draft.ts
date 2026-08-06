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
 * Complete branch target edited by a pane: durable branch settings plus the Thread-level YOLO
 * runtime policy. environmentId is a canonical lowercase UUID route identity.
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
 * Materializes the complete branch draft for a blank pane from the Chat defaults (agent +
 * yolo) and the catalog:
 * - agent model ref -> provider/model selection
 * - variant = agent override or the model's defaultVariant
 * - thinkingLevel = that variant's reasoningEffort or `off`
 * - activeTools = agent.config.tools
 * - environmentId starts null
 *
 * Returns null when no agent is configured or the agent's model/variant cannot be resolved:
 * creating a Thread with empty provider/model/variant would be rejected by the strict mapper,
 * so the caller must surface an explicit error or open the agent picker instead.
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
 * Materializes a full draft from an explicitly selected Agent + catalog (used when the current
 * draft has no valid model selection yet, e.g. a stale Chat agent). Reuses the durable values
 * of an existing frozen draft otherwise.
 */
export function materializeAgentBranchDraft(
  agent: AgentDefinitionDTO,
  models: AgentModelView[],
  existing: BranchDraft | null,
  fallbackYoloEnabled?: boolean,
): BranchDraft | null {
  // A full materialization (no valid existing draft, e.g. stale Chat agent) must keep the
  // Chat default: `?? false` would silently drop a user's yolo=true preference.
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
  // Freeze rule: keep the current model selection / thinking / environment / yolo; adopt the
  // new agent name and its active tool set.
  return {
    ...materialized,
    environmentId: existing.environmentId,
    model: { ...existing.model },
    thinkingLevel: existing.thinkingLevel,
    yoloEnabled: existing.yoloEnabled,
  }
}

/** Initializes a bound pane draft from the durable Thread snapshot (never from Chat defaults). */
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

/** Canonical SET_* command type order for branch diffs (stable product order). */
export const BRANCH_DIFF_COMMAND_TYPES = [
  'SET_ENVIRONMENT',
  'SET_AGENT',
  'SET_MODEL',
  'SET_THINKING_LEVEL',
  'SET_ACTIVE_TOOLS',
  'SET_YOLO',
] as const

/**
 * Builds the minimal settings command diff between the effective base and the draft in the
 * fixed order SET_ENVIRONMENT/SET_AGENT/SET_MODEL/SET_THINKING_LEVEL/SET_ACTIVE_TOOLS/SET_YOLO.
 * Each command carries its own stable clientCommandId from the injected id factory.
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
 * Projects the pending branch target by applying QUEUED SET_* command payloads (in sequence
 * order) over the durable base. Used to avoid resending settings that are already in flight.
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

/** Decimal string comparison used for command sequence ordering. */
export function compareDecimalStrings(left: string, right: string): number {
  try {
    const l = BigInt(left)
    const r = BigInt(right)
    return l < r ? -1 : l > r ? 1 : 0
  } catch {
    return 0
  }
}
