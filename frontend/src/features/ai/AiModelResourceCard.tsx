import { formatJsonSummary } from '@/features/ai/ai-console-utils'
import type { AgentModelDTO } from '@/shared/api/contracts'
import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'

export function ModelResourceCard({
  model,
  onEdit,
  onDelete,
  deletePending,
}: {
  model: AgentModelDTO
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  return (
    <ResourceCardLayout
      icon="model"
      title={model.name}
      subtitle={model.description || `${model.providerName}/${model.name}`}
      rows={[
        ['Provider', model.providerName],
        ['Variant', model.defaultVariant || '-'],
        ['Variants', formatJsonSummary(model.variantsJson)],
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
