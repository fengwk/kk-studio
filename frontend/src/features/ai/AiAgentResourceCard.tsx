import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'
import type { AgentModelView } from '@/features/ai/AgentModelView'
import type { AgentDefinitionDTO } from '@/shared/api/contracts'

function formatAgentModelLabel(model: AgentModelView | undefined, modelId: string | null | undefined): string {
  if (!model) {
    return modelId?.trim() || ''
  }
  const provider = model.providerName?.trim()
  return provider ? `${provider}/${model.name}` : model.name
}

/** 卡片上的 Policy = 编辑页 executionPolicy（maxTurns/maxDepth/...）的紧凑摘要。 */
function formatPolicy(agent: AgentDefinitionDTO): string {
  const policy = agent.config?.executionPolicy
  if (!policy) {
    return ''
  }
  const parts: string[] = []
  if (policy.maxTurns != null) {
    parts.push(`turns ${policy.maxTurns}`)
  }
  if (policy.maxDepth != null) {
    parts.push(`depth ${policy.maxDepth}`)
  }
  if (policy.maxDirectSubagents != null) {
    parts.push(`direct ${policy.maxDirectSubagents}`)
  }
  if (policy.maxTotalSubagents != null) {
    parts.push(`total ${policy.maxTotalSubagents}`)
  }
  return parts.join(' · ')
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
  const environmentName = agent.config?.environmentName?.trim() || ''
  const tools = agent.config?.tools ?? []
  const skills = agent.config?.skills ?? []
  const subagents = agent.config?.allowedSubagents ?? []

  return (
    <ResourceCardLayout
      icon="agent"
      title={agent.name}
      subtitle={agent.description || agent.systemPrompt || agent.name}
      rows={[
        ['Model', modelLabel],
        ['Variant', agent.variant?.trim() || ''],
        ['Env', environmentName],
        { label: 'Tools', tags: tools, limit: 2 },
        { label: 'Skills', tags: skills, limit: 2 },
        { label: 'Subs', tags: subagents, limit: 2 },
        ['Policy', formatPolicy(agent)],
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
