import { getBuiltinModelSelection } from '@/shared/api/contracts/ai-runtime'
import type { HarnessExecutionSelectionDTO } from '@/shared/api/generated/contracts'

/** 组合逻辑名与 Variant 的展示标签；组没有组级 Variant，不伪造默认值。 */
export function formatModelLabel(
  modelName: string | null | undefined,
  variant?: string | null,
): string {
  const name = modelName?.trim() ?? ''
  const resolved = variant?.trim() ?? ''
  if (!name) {
    return resolved || 'unknown-model'
  }
  return resolved ? `${name} (${resolved})` : name
}

/** 执行选择的模型标签：只有 Builtin 执行选择携带模型，其它 Driver 显示为未知模型。 */
export function formatExecutionModelLabel(execution: HarnessExecutionSelectionDTO): string {
  const model = getBuiltinModelSelection(execution)
  return formatModelLabel(model?.modelName, model?.variant)
}
