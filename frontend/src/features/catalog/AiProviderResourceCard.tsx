import { formatDurationMillis } from '@/features/catalog/ai-resource-card-format'
import { ResourceCardLayout } from '@/features/catalog/AiResourceCardLayout'
import { useI18n } from '@/shared/i18n'
import type { ProviderDTO } from '@/shared/api/generated/contracts'

export function ProviderResourceCard({
  provider,
  onEdit,
  onDelete,
  deletePending,
}: {
  provider: ProviderDTO
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
        [t('ai.catalog.form.baseUrl'), provider.baseUrl || t('ai.catalog.card.emptyValue')],
        [
          t('ai.catalog.card.apiKey'),
          provider.credential.configured
            ? t('ai.catalog.card.configured')
            : t('ai.catalog.card.unconfiguredApiKey'),
        ],
        [t('ai.catalog.card.timeout'), formatDurationMillis(provider.modelCallTimeoutMillis)],
        [t('ai.catalog.card.idle'), formatDurationMillis(provider.modelCallIdleTimeoutMillis)],
        [
          t('ai.catalog.card.httpRetry'),
          provider.modelHttpRetryStatusCodes == null
            ? t('ai.catalog.card.httpRetryInherit')
            : (provider.modelHttpRetryStatusCodes.length === 0
                ? t('ai.catalog.card.httpRetryDisabled')
                : provider.modelHttpRetryStatusCodes.join(', ')),
        ],
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
