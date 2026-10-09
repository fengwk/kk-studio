import { ResourceCardLayout } from '@/features/ai/catalog/AiResourceCardLayout'
import { modelRef, type AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { translate, useI18n } from '@/shared/i18n'

/**
 * Agent 展示的必须是实际生效的 model + variant：显式 override 优先，否则回退 model 的 defaultVariant。model 未加载时
 * 无法解析 variant，按引用原样回退并标注未解析，绝不静默编造一个 variant。BUILTIN 的 null model 明确显示未配置，
 * 不回退父模型或 catalog 中的第一个 model。
 */
function formatEffectiveModel(
  model: AgentModelView | undefined,
  modelRefValue: string,
  variantOverride: string | null,
): string {
  if (!modelRefValue) {
    return translate('ai.catalog.card.modelUnconfigured')
  }
  const label = model ? modelRef(model) : modelRefValue
  const override = variantOverride?.trim()
  if (override) {
    return `${label} · ${override}`
  }
  const defaultVariant = model?.config?.defaultVariant?.trim()
  if (!defaultVariant) {
    return `${label} · ${translate('ai.catalog.card.unknownVariant')}`
  }
  return `${label} · ${defaultVariant}${translate('ai.catalog.card.modelDefaultSuffix')}`
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
  const { t } = useI18n()
  const modelRefValue = (agent.model ?? '').trim()
  const model = models.find((item) => modelRef(item) === modelRefValue)
  const effectiveModel = formatEffectiveModel(model, modelRefValue, agent.variant)
  const tools = agent.config.tools
  const skillTags = (agent.config.skills ?? []).map((s) => `${s.packageName} / ${s.name}`)
  const subagents = agent.config.subagents
  // identity 保护只看系统类型，不按名称硬编码：任何 BUILTIN 都不可删除，普通 USER 与之无差别。
  const builtin = agent.type === 'BUILTIN'

  return (
    <ResourceCardLayout
      icon="agent"
      title={agent.name}
      subtitle={agent.description || agent.systemPrompt || agent.name}
      badge={
        builtin ? (
          <span className="status-pill is-neutral">{t('ai.catalog.card.builtinBadge')}</span>
        ) : undefined
      }
      rows={[
        [t('ai.catalog.card.effectiveModel'), effectiveModel],
        { label: t('ai.catalog.card.tools'), tags: tools, limit: 2 },
        { label: t('ai.catalog.card.skills'), tags: skillTags, limit: 2 },
        { label: t('ai.catalog.card.subagents'), tags: subagents, limit: 2 },
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
      deleteDisabled={builtin}
    />
  )
}
