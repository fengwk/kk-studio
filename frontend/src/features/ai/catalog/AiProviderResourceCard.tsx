import { ResourceCardLayout } from '@/features/ai/catalog/AiResourceCardLayout'
import type { AgentProviderDTO } from '@/shared/api/contracts/ai-catalog'
import { translate, useI18n } from '@/shared/i18n'

function formatMs(value: number | string | null | undefined): string {
  if (value == null || value === '') {
    return translate('ai.catalog.card.emptyValue')
  }
  const n = Number(value)
  if (!Number.isFinite(n)) {
    return String(value)
  }
  if (n >= 60_000) {
    return translate('ai.catalog.card.seconds', { value: Math.round(n / 1000) })
  }
  return translate('ai.catalog.card.milliseconds', { value: n })
}

export function ProviderResourceCard({
  provider,
  onEdit,
  onDelete,
  deletePending,
}: {
  provider: AgentProviderDTO
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const { t } = useI18n()
  return (
    <ResourceCardLayout
      icon="provider"
      title={provider.name}
      subtitle={provider.description || provider.baseUrl || provider.providerType}
      rows={[
        [t('ai.catalog.card.type'), provider.providerType],
        [t('ai.catalog.card.url'), provider.baseUrl || t('ai.catalog.card.emptyValue')],
        [
          t('ai.catalog.card.apiKey'),
          provider.configured
            ? t('ai.catalog.card.configured')
            : t('ai.catalog.card.unconfiguredApiKey'),
        ],
        {
          pairs: [
            { label: t('ai.catalog.card.timeout'), value: formatMs(provider.modelCallTimeoutMillis) },
            { label: t('ai.catalog.card.idle'), value: formatMs(provider.modelCallIdleTimeoutMillis) },
          ],
        },
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
