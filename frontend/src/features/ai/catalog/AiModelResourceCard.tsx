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
import { translate, useI18n } from '@/shared/i18n'

function formatAbilities(model: AgentModelView): string {
  const draft = toModelDraft(model)
  const flags: string[] = []
  if (draft.tools) {
    flags.push(translate('ai.catalog.card.toolsAbility'))
  }
  if (draft.reasoning) {
    flags.push(translate('ai.catalog.card.reasoningAbility'))
  }
  const modalityText = formatCompactList(draft.inputModalities, 3, '')
  if (modalityText) {
    flags.push(modalityText)
  }
  return flags.length > 0 ? flags.join(' · ') : translate('ai.catalog.card.emptyValue')
}

function formatLimit(model: AgentModelView): string {
  const contextWindow = extractContextWindow(model)
  const output = extractMaxOutputTokens(model)
  const parts: string[] = []
  if (contextWindow) {
    parts.push(translate('ai.catalog.card.contextLimit', { value: contextWindow }))
  }
  if (output) {
    parts.push(translate('ai.catalog.card.outputLimit', { value: output }))
  }
  return parts.length > 0 ? parts.join(' · ') : translate('ai.catalog.card.emptyValue')
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
  const { t } = useI18n()
  const variants = extractVariantIdsFromModel(model)
  const defaultVariant = extractDefaultVariantFromModel(model)
  const ref = modelRef(model)
  return (
    <ResourceCardLayout
      icon="model"
      title={ref}
      subtitle={model.description || ref}
      rows={[
        [t('ai.catalog.card.ref'), ref],
        [t('ai.catalog.card.limit'), formatLimit(model)],
        [t('ai.catalog.card.ability'), formatAbilities(model)],
        [t('ai.catalog.card.default'), defaultVariant || t('ai.catalog.card.emptyValue')],
        { label: t('ai.catalog.card.variants'), tags: variants, limit: 3 },
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
