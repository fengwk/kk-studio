import type {
  HarnessBranchSettingsDTO,
  HarnessCommandOwnerDTO,
  HarnessCommandTargetDTO,
  HarnessExecutionSelectionDTO,
  HarnessImageInputTier,
  HarnessModelSelectionDTO,
} from '@/shared/api/generated/contracts'

export type {
  EntryType,
  HarnessCommandBatchDTO,
  HarnessCommandCreateDTO,
  HarnessThreadCommandBatchDTO,
  HarnessUserMessageContentDTO,
} from '@/shared/api/generated/contracts'

/** 创建型命令 target；后端联合类型直接复用生成契约。 */
export type AgentCommandTargetDTO = HarnessCommandTargetDTO

/** 不透明的产品归属身份。 */
export type AgentRuntimeOwnerDTO = HarnessCommandOwnerDTO

/** 图片输入档位；Wire 与 durable 均采用 720P / 1080P / ORIGINAL。 */
export type ImageInputTier = HarnessImageInputTier

const BUILTIN_DRIVER = 'builtin'

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

export type { HarnessBranchSettingsDTO }
