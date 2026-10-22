import type { AgentModelDTO } from '@/shared/api/contracts/ai-catalog'

/**
 * {@link AgentModelDTO} 的客户端投影。逻辑名已全局唯一，因此不再补充 provider 前缀；
 * 单 Model 与组的展示/引用都直接使用 {@link AgentModelView.name}。
 */
export type AgentModelView = AgentModelDTO

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

export function isGroupModel(model?: AgentModelView | null): boolean {
  return model?.kind === 'GROUP'
}

export function toAgentModelViews(models: AgentModelDTO[]): AgentModelView[] {
  return models.map((model) => ({ ...model }))
}
