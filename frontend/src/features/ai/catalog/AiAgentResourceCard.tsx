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
  const modelLabel = formatAgentModelLabel(model, agent.model)
  const toolIds = agent.config.toolIds
  const skills = agent.config.skills
  const subagents = agent.config.subagents

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
