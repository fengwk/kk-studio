import type { AgentDefinitionDTO, AgentModelDTO } from '@/shared/api/contracts'
import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'

export function AgentResourceCard({
  agent,
  models = [],
  onStart,
  onEdit,
  onDelete,
  deletePending,
}: {
  agent: AgentDefinitionDTO
  models?: AgentModelDTO[]
  onStart: () => void
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const model = models.find((item) => String(item.id) === String(agent.modelId))
  const modelLabel = model
    ? `${model.providerName}/${model.name}`
    : agent.modelId || '-'
  const environmentName = agent.config?.environmentName?.trim() || '（无）'
  return (
    <ResourceCardLayout
      icon="agent"
      title={agent.name}
      subtitle={agent.description || agent.systemPrompt || agent.name}
      rows={[
        ['Model', modelLabel],
        ['Variant', agent.variant || '-'],
        ['Environment', environmentName],
      ]}
      onStart={onStart}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
