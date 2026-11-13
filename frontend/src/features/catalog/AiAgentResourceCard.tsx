import { ResourceCardLayout } from '@/features/catalog/AiResourceCardLayout'
import {
  formatModelLabel,
} from '@/features/catalog/model-labels'
import { translate, useI18n } from '@/shared/i18n'
import type { AgentDTO, ModelDTO } from '@/shared/api/generated/contracts'
import { getBuiltinModelSelection } from '@/shared/api/contracts/ai-runtime'

/**
 * Agent 展示实际生效的 model + variant：显式 override 优先，否则使用 model 的 defaultVariant。model 未加载且没有
 * override 时标注未解析，不编造 variant。缺模型引用时明确显示未配置，
 * 不回退父模型或 catalog 中的第一个 model。
 */
function formatEffectiveModel(
  model: ModelDTO | undefined,
  modelRefValue: string,
  variantOverride: string | null,
): string {
  if (!modelRefValue) {
    return translate('ai.catalog.card.modelUnconfigured')
  }
  const label = model?.name || modelRefValue
  if (model?.type === 'GROUP') {
    return label
  }
  const override = variantOverride?.trim()
  if (override) {
    return formatModelLabel(label, override)
  }
  const defaultVariant = model?.config?.defaultVariant?.trim()
  return defaultVariant
    ? `${formatModelLabel(label, defaultVariant)}${translate('ai.catalog.card.modelDefaultSuffix')}`
    : `${label} · ${translate('ai.catalog.card.unknownVariant')}`
}

export function AgentResourceCard({
  agent,
  models = [],
  onEdit,
  onDelete,
  deletePending,
}: {
  agent: AgentDTO
  models?: ModelDTO[]
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const { t } = useI18n()
  const selection = getBuiltinModelSelection(agent.execution)
  const modelRefValue = (selection?.modelName ?? '').trim()
  const model = models.find((item) => item.name === modelRefValue)
  const effectiveModel = agent.execution && agent.execution.driver !== 'builtin'
    ? agent.execution.driver
    : formatEffectiveModel(model, modelRefValue, selection?.variant ?? null)
  const tools = agent.config.tools
  const skillTags = (agent.config.skills ?? []).map((s) => `${s.packageName} / ${s.name}`)
  const subagents = agent.config.subagents

  return (
    <ResourceCardLayout
      icon="agent"
      title={agent.name}
      subtitle={agent.description || agent.systemPrompt || agent.name}
      rows={[
        [t(agent.execution && agent.execution.driver !== 'builtin'
          ? 'ai.catalog.form.executionDriver'
          : 'ai.catalog.card.effectiveModel'), effectiveModel],
        { label: t('ai.catalog.card.tools'), tags: tools, limit: 2 },
        { label: t('ai.catalog.card.skills'), tags: skillTags, limit: 2 },
        { label: t('ai.catalog.card.subagents'), tags: subagents, limit: 2 },
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
