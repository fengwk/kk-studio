import { ResourceCardLayout } from '@/features/ai/catalog/AiResourceCardLayout'
import { modelRef, type AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { translate, useI18n } from '@/shared/i18n'

function formatAgentModelLabel(model: AgentModelView | undefined, modelName: string): string {
  if (!model) {
    return modelName.trim() || translate('ai.catalog.card.unknownModel')
  }
  return modelRef(model)
}

/**
 * Agent 展示的必须是实际生效的 model + variant：显式 override 优先，否则回退 model 的 defaultVariant。model 未加载时
 * 无法解析 variant，按引用原样回退并标注未解析，绝不静默编造一个 variant。
 */
function formatEffectiveModel(
  model: AgentModelView | undefined,
  agent: AgentDefinitionDTO,
): string {
  const label = formatAgentModelLabel(model, agent.model)
  const override = agent.variant?.trim()
  if (override) {
    return `${label} · ${override}`
  }
  const defaultVariant = model?.config?.defaultVariant?.trim()
  if (!defaultVariant) {
    return `${label} · ${translate('ai.catalog.card.unknownVariant')}`
  }
  return `${label} · ${defaultVariant}${translate('ai.catalog.card.modelDefaultSuffix')}`
}

export function AgentResourceCard({
  agent,
  models = [],
  onEdit,
  onDelete,
  deletePending,
}: {
  agent: AgentDefinitionDTO
  models?: AgentModelView[]
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const { t } = useI18n()
  const model = models.find((item) => modelRef(item) === agent.model)
  const effectiveModel = formatEffectiveModel(model, agent)
  const toolIds = agent.config.toolIds
  const skills = agent.config.skills
  const subagents = agent.config.subagents

  return (
    <ResourceCardLayout
      icon="agent"
      title={agent.name}
      subtitle={agent.description || agent.systemPrompt || agent.name}
      rows={[
        [t('ai.catalog.card.effectiveModel'), effectiveModel],
        { label: t('ai.catalog.card.tools'), tags: toolIds, limit: 2 },
        { label: t('ai.catalog.card.skills'), tags: skills, limit: 2 },
        { label: t('ai.catalog.card.subagents'), tags: subagents, limit: 2 },
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
