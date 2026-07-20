import {
  extractContextWindow,
  extractDefaultVariantFromModel,
  extractMaxOutputTokens,
  extractVariantNamesFromModel,
  toModelDraft,
} from '@/features/ai/ai-model-draft-codec'
import { formatCompactList } from '@/features/ai/ai-resource-card-format'
import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'
import type { AgentModelView } from '@/features/ai/AgentModelView'

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
  const variants = extractVariantNamesFromModel(model)
  const defaultVariant = extractDefaultVariantFromModel(model)
  return (
    <ResourceCardLayout
      icon="model"
      title={model.name}
      subtitle={model.description || `${model.providerName || model.providerId}/${model.name}`}
      rows={[
        ['Provider', model.providerName || String(model.providerId)],
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
