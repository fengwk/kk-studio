import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'
import { modelRef, type AgentModelView } from '@/features/ai/AgentModelView'
import type { AgentDefinitionDTO } from '@/shared/api/contracts'

function formatAgentModelLabel(model: AgentModelView | undefined, modelId: string): string {
  if (!model) {
    return modelId.trim() || 'unknown-model'
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
        ['Default Model', modelLabel],
        ['Variant', agent.variant?.trim() ? agent.variant.trim() : '模型默认'],
        ['Env', environmentName],
        { label: 'Tools', tags: tools, limit: 2 },
        { label: 'Skills', tags: skills, limit: 2 },
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
