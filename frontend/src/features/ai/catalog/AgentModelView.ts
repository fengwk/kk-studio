import type { AgentModelDTO } from '@/shared/api/contracts/ai-catalog'

/**
 * {@link AgentModelDTO} 的客户端投影，补充了 console 需要的展示字段，但 wire 格式刻意省略这些字段。
 * 当前唯一补充的字段是 {@link AgentModelView.providerName}，由模型身份直接携带。
 *
 * <p>本视图刻意不通过 {@code shared/api/contracts/ai-catalog.ts} 导出；后端 DTO 保持精简且可发现，
 * 而特性局部投影则贴近真正使用它们的消费者。
 */
export interface AgentModelView extends AgentModelDTO {
  /** 该模型自有的规范 provider 标识。 */
  providerName: string
}

/**
 * 面向用户的规范模型标识：{@code provider/model}。
 *
 * <p>这是卡片、选择器、页脚和确认框使用的统一展示/引用标签。
 */
export function formatModelRef(
  providerName: string | null | undefined,
  modelName: string | null | undefined,
): string {
  const provider = providerName?.trim() ?? ''
  const model = modelName?.trim() ?? ''
  if (provider && model) {
    // 避免调用方传入已拼装好的 ref 时被重复拼接前缀。
    if (model === provider || model.startsWith(`${provider}/`)) {
      return model
    }
    return `${provider}/${model}`
  }
  return model || provider || 'unknown-model'
}

/** {@link AgentModelView} 的便捷别名。 */
export function modelRef(
  model: Pick<AgentModelView, 'name' | 'providerName'>,
): string {
  return formatModelRef(model.providerName, model.name)
}

/**
 * Model 已经携带规范的 provider name；为同样需要 model 专属辅助函数的调用方保留特性局部视图，
 * 而无需重新引入 resource-id 关联。
 */
export function toAgentModelViews(models: AgentModelDTO[]): AgentModelView[] {
  return models.map((model) => ({ ...model }))
}