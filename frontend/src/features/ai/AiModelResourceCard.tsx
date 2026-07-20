import { extractContextWindow, extractVariantNamesFromModel } from '@/features/ai/ai-model-draft-codec'
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
  const profiles = extractVariantNamesFromModel(model)
  const contextWindow = extractContextWindow(model)
  return (
    <ResourceCardLayout
      icon="model"
      title={model.name}
      subtitle={model.description || `${model.providerName || model.providerId}/${model.name}`}
      rows={[
        ['Provider', model.providerName || String(model.providerId)],
        ['Context', contextWindow ? String(contextWindow) : '-'],
        ['Profiles', profiles.join(', ') || '-'],
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
