import type { AgentDefinitionDTO } from '@/shared/api/contracts'
import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'

export function AgentResourceCard({
  agent,
  onStart,
  onEdit,
  onDelete,
  deletePending,
}: {
  agent: AgentDefinitionDTO
  onStart: () => void
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  return (
    <ResourceCardLayout
      icon="agent"
      title={agent.name}
      subtitle={agent.description || agent.systemPrompt || agent.name}
      rows={[
        ['Model', `${agent.defaultProviderName}/${agent.defaultModelName}`],
        ['Variant', agent.defaultVariant || '-'],
      ]}
      onStart={onStart}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
