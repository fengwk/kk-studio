import { ResourceCardLayout } from '@/features/ai/catalog/AiResourceCardLayout'
import { modelRef, type AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { translate, useI18n } from '@/shared/i18n'

function formatAgentModelLabel(model: AgentModelView | undefined, modelId: string): string {
  if (!model) {
    return modelId.trim() || translate('ai.catalog.card.unknownModel')
  }
  return modelRef(model)
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
  const model = models.find((item) => String(item.id) === String(agent.modelId))
  const modelLabel = formatAgentModelLabel(model, agent.modelId)
  const environmentName = agent.config.environmentName?.trim() || ''
  const tools = agent.config.tools
  const skills = agent.config.skills

  return (
    <ResourceCardLayout
      icon="agent"
      title={agent.name}
      subtitle={agent.description || agent.systemPrompt || agent.name}
      rows={[
        [t('ai.catalog.card.defaultModel'), modelLabel],
        [
          t('ai.catalog.card.variant'),
          agent.variant?.trim() ? agent.variant.trim() : t('ai.catalog.card.modelDefault'),
        ],
        [t('ai.catalog.card.environment'), environmentName],
        { label: t('ai.catalog.card.tools'), tags: tools, limit: 2 },
        { label: t('ai.catalog.card.skills'), tags: skills, limit: 2 },
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
