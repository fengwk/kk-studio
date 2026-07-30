import {
  extractContextWindow,
  extractDefaultVariantFromModel,
  extractMaxOutputTokens,
  extractVariantIdsFromModel,
  toModelDraft,
} from '@/features/ai/catalog/ai-model-draft-codec'
import { formatCompactList } from '@/features/ai/catalog/ai-resource-card-format'
import { ResourceCardLayout } from '@/features/ai/catalog/AiResourceCardLayout'
import { modelRef, type AgentModelView } from '@/features/ai/catalog/AgentModelView'

function formatAbilities(model: AgentModelView): string {
  const draft = toModelDraft(model)
  const flags: string[] = []
  if (draft.tools) {
    flags.push('tools')
  }
  if (draft.reasoning) {
    flags.push('reasoning')
  }
  const modalityText = formatCompactList(draft.inputModalities, 3, '')
  if (modalityText) {
    flags.push(modalityText)
  }
  return flags.length > 0 ? flags.join(' · ') : '—'
}

function formatLimit(model: AgentModelView): string {
  const contextWindow = extractContextWindow(model)
  const output = extractMaxOutputTokens(model)
  const parts: string[] = []
  if (contextWindow) {
    parts.push(`ctx ${contextWindow}`)
  }
  if (output) {
    parts.push(`out ${output}`)
  }
  return parts.length > 0 ? parts.join(' · ') : '—'
}

export function ModelResourceCard({
  model,
  onEdit,
  onDelete,
  deletePending,
}: {
  model: AgentModelView
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const variants = extractVariantIdsFromModel(model)
  const defaultVariant = extractDefaultVariantFromModel(model)
  const ref = modelRef(model)
  return (
    <ResourceCardLayout
      icon="model"
      title={ref}
      subtitle={model.description || ref}
      rows={[
        ['Ref', ref],
        ['Limit', formatLimit(model)],
        ['Ability', formatAbilities(model)],
        ['Default', defaultVariant || '—'],
        { label: 'Variants', tags: variants, limit: 3 },
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
