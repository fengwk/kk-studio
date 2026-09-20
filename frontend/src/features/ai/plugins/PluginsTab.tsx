import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { pluginsService } from '@/shared/api/plugins-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import { PluginCard } from '@/features/ai/plugins/PluginCard'
import { PluginConnectModal } from '@/features/ai/plugins/PluginConnectModal'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

export function PluginsTab() {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [connectTarget, setConnectTarget] = useState<PluginDTO | null>(null)
  const [disconnectTarget, setDisconnectTarget] = useState<PluginDTO | null>(null)
  const [disconnectError, setDisconnectError] = useState<string | null>(null)

  const pluginsQuery = useQuery({
    queryKey: queryKeys.plugins.list,
    queryFn: () => pluginsService.listPlugins(),
  })

  const disconnectMutation = useMutation({
    mutationFn: (pluginId: string) => pluginsService.disconnectAuth(pluginId),
    onSuccess: () => {
      setDisconnectTarget(null)
      setDisconnectError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.plugins.all })
    },
    onError: (err: unknown) => {
      setDisconnectError(err instanceof Error ? err.message : String(err))
    },
  })

  if (pluginsQuery.isLoading) {
    return (
      <div className="state-block" role="status">
        {t('settings.loading')}
      </div>
    )
  }

  if (pluginsQuery.isError) {
    return (
      <div className="state-block is-error" role="alert">
        {pluginsQuery.error instanceof Error
          ? pluginsQuery.error.message
          : String(pluginsQuery.error)}
      </div>
    )
  }

  const plugins = pluginsQuery.data ?? []

  return (
    <div className="plugins-tab-container" style={{ padding: '8px 0' }}>
      {plugins.length === 0 ? (
        <div className="state-block" role="status">
          {t('plugins.empty')}
        </div>
      ) : (
        <div className="cards-grid">
          {plugins.map((plugin) => (
            <PluginCard
              key={plugin.pluginId}
              plugin={plugin}
              onConnect={() => setConnectTarget(plugin)}
              onDisconnect={() => {
                setDisconnectError(null)
                setDisconnectTarget(plugin)
              }}
            />
          ))}
        </div>
      )}

      {connectTarget && (
        <PluginConnectModal
          plugin={connectTarget}
          onClose={() => setConnectTarget(null)}
          onSuccess={() => {
            setConnectTarget(null)
            void queryClient.invalidateQueries({ queryKey: queryKeys.plugins.all })
          }}
        />
      )}

      {disconnectTarget && (
        <ConfirmActionModal
          modal={{
            title: t('plugins.disconnect'),
            description: t('plugins.disconnectConfirm', { name: disconnectTarget.name }),
            confirmLabel: t('plugins.disconnect'),
            tone: 'danger',
            error: disconnectError,
            onConfirm: () => {
              disconnectMutation.mutate(disconnectTarget.pluginId)
            },
          }}
          pending={disconnectMutation.isPending}
          onClose={() => {
            setDisconnectTarget(null)
            setDisconnectError(null)
          }}
        />
      )}
    </div>
  )
}
