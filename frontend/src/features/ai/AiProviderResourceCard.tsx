import type { AgentProviderDTO } from '@/shared/api/contracts'
import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'

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
        ['Base URL', provider.baseUrl || '-'],
        ['Model timeout', `${provider.modelCallTimeoutMillis} ms`],
        ['Idle timeout', `${provider.modelCallIdleTimeoutMillis} ms`],
      ]}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
