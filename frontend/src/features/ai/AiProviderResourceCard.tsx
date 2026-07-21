import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'
import type { AgentProviderDTO } from '@/shared/api/contracts'

function formatMs(value: number | string | null | undefined): string {
  if (value == null || value === '') {
    return '—'
  }
  const n = Number(value)
  if (!Number.isFinite(n)) {
    return String(value)
  }
  if (n >= 60_000) {
    return `${Math.round(n / 1000)}s`
  }
  return `${n}ms`
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
  return (
    <ResourceCardLayout
      icon="provider"
      title={provider.name}
      subtitle={provider.description || provider.baseUrl || provider.providerType}
      rows={[
        ['Type', provider.providerType],
        ['URL', provider.baseUrl || '—'],
        ['API Key', provider.configured ? '已配置' : '无 API Key（无认证请求）'],
        {
          pairs: [
            { label: 'Timeout', value: formatMs(provider.modelCallTimeoutMillis) },
            { label: 'Idle', value: formatMs(provider.modelCallIdleTimeoutMillis) },
          ],
        },
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
