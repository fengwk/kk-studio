import type { AgentDefinitionDTO, AgentModelDTO } from '@/shared/api/contracts'
import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'

function formatAgentModelLabel(model: AgentModelDTO | undefined, modelId: string | null | undefined): string {
  if (!model) {
    return modelId?.trim() || '-'
  }
  const provider = model.providerName?.trim()
  return provider ? `${provider}/${model.name}` : model.name
}

export function AgentResourceCard({
  agent,
  models = [],
  onEdit,
  onDelete,
  deletePending,
}: {
  agent: AgentDefinitionDTO
  models?: AgentModelDTO[]
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const model = models.find((item) => String(item.id) === String(agent.modelId))
  const modelLabel = formatAgentModelLabel(model, agent.modelId)
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
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
