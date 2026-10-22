import {
  extractContextWindow,
  extractDefaultVariantFromModel,
  extractMaxOutputTokens,
  extractVariantIdsFromModel,
} from '@/features/catalog/ai-model-draft-codec'
import { formatCompactList } from '@/features/catalog/ai-resource-card-format'
import { ResourceCardLayout } from '@/features/catalog/AiResourceCardLayout'
import type { AgentModelView } from '@/features/catalog/AgentModelView'
import { translate, useI18n } from '@/shared/i18n'

function formatAbilities(model: AgentModelView): string {
  if (!model.config) {
    return translate('ai.catalog.card.emptyValue')
  }
  const { abilities } = model.config
  const flags: string[] = []
  if (abilities.tools) {
    flags.push(translate('ai.catalog.card.toolsAbility'))
  }
  if (abilities.reasoning) {
    flags.push(translate('ai.catalog.card.reasoningAbility'))
  }
  const modalityText = formatCompactList(abilities.inputModalities, 3, '')
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

/** 组卡只显示成员顺序、触发码与固定冷却；不把组当成第二个定价或能力配置入口。 */
function formatGroupMembers(model: AgentModelView): string {
  const members = model.group?.members ?? []
  if (members.length === 0) {
    return translate('ai.catalog.card.emptyValue')
  }
  const visible = members.slice(0, 3).map((member) => member.modelName)
  const rest = members.length - visible.length
  return rest > 0
    ? `${visible.join(' → ')} · ${translate('ai.catalog.card.moreMembers', { count: rest })}`
    : visible.join(' → ')
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
  const group = model.kind === 'GROUP'
  const variants = extractVariantIdsFromModel(model)
  const defaultVariant = extractDefaultVariantFromModel(model)
  return (
    <ResourceCardLayout
      icon="model"
      title={model.name}
      subtitle={model.description || model.name}
      rows={group ? [
        [t('ai.catalog.card.groupMembers'), formatGroupMembers(model)],
        [
          t('ai.catalog.card.fallbackStatusCodes'),
          model.group?.fallbackStatusCodes.join(', ') || t('ai.catalog.card.emptyValue'),
        ],
        [
          t('ai.catalog.card.cooldownSeconds'),
          `${model.group?.cooldownSeconds ?? 0} s`,
        ],
      ] : [
        [t('ai.catalog.card.provider'), model.providerName || t('ai.catalog.card.emptyValue')],
        [t('ai.catalog.card.modelId'), model.modelId || t('ai.catalog.card.emptyValue')],
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
