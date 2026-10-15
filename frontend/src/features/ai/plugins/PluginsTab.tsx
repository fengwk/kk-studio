import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { isConflictError } from '@/shared/api/client'
import { pluginsService } from '@/shared/api/plugins-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import { PluginCard } from '@/features/ai/plugins/PluginCard'
import { PluginConnectModal } from '@/features/ai/plugins/PluginConnectModal'
import { PluginConfigModal } from '@/features/ai/plugins/PluginConfigModal'
import { PluginConnectionCheckModal } from '@/features/ai/plugins/PluginConnectionCheckModal'
import {
  pluginConnectionCheckExpected,
  type PluginConnectionCheckRun,
} from '@/features/ai/plugins/plugin-connection-check'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

export function PluginsTab() {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [configureTarget, setConfigureTarget] = useState<PluginDTO | null>(null)
  const [connectTarget, setConnectTarget] = useState<PluginDTO | null>(null)
  const [disconnectTarget, setDisconnectTarget] = useState<PluginDTO | null>(null)
  const [disconnectError, setDisconnectError] = useState<string | null>(null)
  const [connectionCheck, setConnectionCheck] = useState<PluginConnectionCheckRun | null>(null)
  /**
   * 检查只由用户显式点击触发：`runRef` 递增标记当前这一次运行，迟到的旧响应直接丢弃，
   * `pendingRef` 记录仍在校验中的 Plugin，保证同一 Plugin 不会被重复提交。
   */
  const checkRunRef = useRef(0)
  const pendingCheckRef = useRef<string | null>(null)

  const pluginsQuery = useQuery({
    queryKey: queryKeys.plugins.list,
    queryFn: () => pluginsService.listPlugins(),
  })

  const startConnectionCheck = (plugin: PluginDTO) => {
    const expected = pluginConnectionCheckExpected(plugin)
    // 未声明能力、尚未应用或本地 pending 时根本不发请求，也绝不提交 null 坐标。
    if (expected === null || pendingCheckRef.current === plugin.pluginId) {
      return
    }
    const runId = checkRunRef.current + 1
    checkRunRef.current = runId
    pendingCheckRef.current = plugin.pluginId
    setConnectionCheck({
      pluginId: plugin.pluginId,
      ...expected,
      phase: 'pending',
      result: null,
      errorMessage: null,
    })

    pluginsService
      .checkConnection(plugin.pluginId, expected)
      .then((result) => {
        if (checkRunRef.current !== runId) {
          return
        }
        pendingCheckRef.current = null
        // 结果必须与本次请求的安装身份和坐标完全一致，跨 ID/跨坐标结果一律拒绝。
        const matches =
          result.pluginId === plugin.pluginId
          && result.appliedEpoch === expected.expectedEpoch
          && result.appliedRevision === expected.expectedRevision
        setConnectionCheck({
          pluginId: plugin.pluginId,
          ...expected,
          phase: matches ? 'done' : 'mismatch',
          result: matches ? result : null,
          errorMessage: null,
        })
      })
      .catch((error: unknown) => {
        if (checkRunRef.current !== runId) {
          return
        }
        pendingCheckRef.current = null
        if (isConflictError(error)) {
          setConnectionCheck({
            pluginId: plugin.pluginId,
            ...expected,
            phase: 'conflict',
            result: null,
            errorMessage: null,
          })
          // 冲突说明列表坐标已过期：刷新列表即可，绝不自动重发检查。
          void queryClient.invalidateQueries({ queryKey: queryKeys.plugins.all })
          return
        }
        setConnectionCheck({
          pluginId: plugin.pluginId,
          ...expected,
          phase: 'failed',
          result: null,
          errorMessage: error instanceof Error && error.message ? error.message : null,
        })
      })
  }

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
    return <ResourceCardSkeleton label={t('settings.loading')} />
  }

  if (pluginsQuery.isError) {
    return (
      <StateBlock
        title={
          pluginsQuery.error instanceof Error
            ? pluginsQuery.error.message
            : String(pluginsQuery.error)
        }
        tone="danger"
      />
    )
  }

  const plugins = pluginsQuery.data ?? []
  // 弹层永远读取列表中的当前 Plugin：列表换版后旧结果只能按陈旧呈现，不会被当作当前健康。
  const connectionCheckPlugin =
    connectionCheck === null
      ? null
      : plugins.find((plugin) => plugin.pluginId === connectionCheck.pluginId) ?? null

  return (
    <>
      {plugins.length === 0 ? (
        <StateBlock title={t('plugins.empty')} />
      ) : (
        <ResourceGrid>
          {plugins.map((plugin) => (
            <PluginCard
              key={plugin.pluginId}
              plugin={plugin}
              onConfigure={() => setConfigureTarget(plugin)}
              onConnect={() => setConnectTarget(plugin)}
              onDisconnect={() => {
                setDisconnectError(null)
                setDisconnectTarget(plugin)
              }}
              onCheckConnection={() => startConnectionCheck(plugin)}
              connectionCheckPending={
                connectionCheck?.pluginId === plugin.pluginId
                && connectionCheck.phase === 'pending'
              }
            />
          ))}
        </ResourceGrid>
      )}

      {connectionCheckPlugin && connectionCheck ? (
        <PluginConnectionCheckModal
          plugin={connectionCheckPlugin}
          run={connectionCheck}
          onClose={() => setConnectionCheck(null)}
        />
      ) : null}

      {configureTarget && (
        <PluginConfigModal
          plugin={configureTarget}
          onClose={() => setConfigureTarget(null)}
          onSuccess={() => {
            setConfigureTarget(null)
            void queryClient.invalidateQueries({ queryKey: queryKeys.plugins.all })
          }}
        />
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
    </>
  )
}
