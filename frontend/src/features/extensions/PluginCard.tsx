import { Activity, LogIn, LogOut, Plug, Settings } from 'lucide-react'
import { Badge, type BadgeTone } from '@/shared/ui/data/Badge'
import { useI18n, type AppLocale } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ResourceCard, type ResourceCardMetaRow } from '@/shared/ui/cards/ResourceCard'
import { pluginConnectionCheckExpected } from '@/features/extensions/plugin-connection-check'
import type { PluginDTO } from '@/shared/api/generated/contracts'

/** 后端 Instant 的 wire 约定是 epoch seconds。 */
function formatDateTime(value: number | null | undefined, locale: AppLocale): string {
  if (value == null) {
    return '—'
  }
  return new Date(value * 1000).toLocaleString(locale)
}

function statusTone(status: string): BadgeTone {
  switch (status) {
    case 'CONNECTED':
      return 'success'
    case 'REFRESH_FAILED':
    case 'REFRESH_UNCERTAIN':
      return 'warning'
    case 'REAUTH_REQUIRED':
      return 'danger'
    case 'NOT_CONNECTED':
    default:
      return 'warning'
  }
}

export function PluginCard({
  plugin,
  onConfigure,
  onConnect,
  onDisconnect,
  onCheckConnection,
  connectionCheckPending,
}: {
  plugin: PluginDTO
  onConfigure: () => void
  onConnect: () => void
  onDisconnect: () => void
  onCheckConnection: () => void
  /** 本 Plugin 的显式连接检查正在进行：入口必须禁用，避免重复请求。 */
  connectionCheckPending: boolean
}) {
  const { t, locale } = useI18n()

  const isDeepLink = plugin.authKind?.type === 'DEEP_LINK'
  const isConnected = plugin.status === 'CONNECTED'
  const isNotConnected = plugin.status === 'NOT_CONNECTED'

  // 修订不同或存在 applicationError 即为待应用状态，不能展示为全局已生效
  const isPendingApplication =
    plugin.applicationError !== null ||
    plugin.desiredRevision !== plugin.appliedRevision

  // 能力来自实例 metadata；未应用或坐标缺失时不提供可提交的检查坐标。
  const connectionCheckAvailable = pluginConnectionCheckExpected(plugin) !== null

  const activeCapabilities = [
    ...plugin.harnessToolNames,
    ...plugin.canvasFunctionNames,
  ]

  const rows: ResourceCardMetaRow[] = [
    [
      t('plugins.desiredRevision'),
      `Rev ${plugin.desiredRevision}`,
    ],
    [
      t('plugins.appliedRevision'),
      `Rev ${plugin.appliedRevision ?? '—'}`,
    ],
    [
      t('plugins.capabilities'),
      activeCapabilities.length > 0 ? activeCapabilities.join(', ') : t('plugins.capabilities.none'),
    ],
  ]

  if (plugin.applicationError) {
    rows.push({
      label: t('plugins.applicationStatus'),
      value: (
        <span
          className="inline-hint danger"
          role="status"
          data-testid={`plugin-app-error-${plugin.pluginId}`}
        >
          {plugin.applicationError}
        </span>
      ),
      wrap: true,
    })
  }

  // 只有 DEEP_LINK 展示 region/token/refresh 元数据
  if (isDeepLink) {
    rows.push(
      [t('plugins.region'), plugin.region || '—'],
      [t('plugins.tokenExpiresAt'), formatDateTime(plugin.expiresAt, locale)],
      [t('plugins.nextRefreshAt'), formatDateTime(plugin.nextRefreshAt, locale)],
      [t('plugins.lastRefreshedAt'), formatDateTime(plugin.lastRefreshedAt, locale)],
    )

  }

  if (plugin.lastRefreshError) {
    rows.push({
      label: t('plugins.lastRefreshError'),
      value: (
        <span className="inline-hint danger" role="alert">
          {plugin.lastRefreshError}
        </span>
      ),
      wrap: true,
    })
  }

  return (
    <ResourceCard
      className="plugin-card"
      icon={<Plug aria-hidden="true" />}
      title={plugin.name}
      subtitle={plugin.pluginId}
      badge={
        <>
          <Badge mono>v{plugin.version}</Badge>
          <Badge
            tone={isPendingApplication ? 'warning' : 'success'}
            data-testid={`plugin-app-status-${plugin.pluginId}`}
          >
            {isPendingApplication
              ? t('plugins.applicationStatus.pending')
              : t('plugins.applicationStatus.applied')}
          </Badge>
          {plugin.authKind && plugin.status ? (
            <Badge
              tone={statusTone(plugin.status)}
              data-testid={`plugin-status-${plugin.pluginId}`}
            >
              {t(`plugins.status.${plugin.status}`) || plugin.status}
            </Badge>
          ) : null}
        </>
      }
      meta={rows}
      actions={
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Button
            size="compact"
            variant="ghost"
            aria-label={`${t('plugins.configure')} ${plugin.name}`}
            onClick={onConfigure}
          >
            <Settings size={14} aria-hidden="true" />
            {t('plugins.configure')}
          </Button>

          {plugin.connectionCheckSupported ? (
            <Button
              size="compact"
              variant="ghost"
              aria-label={`${t('plugins.connectionCheck')} ${plugin.name}`}
              disabled={!connectionCheckAvailable}
              loading={connectionCheckPending}
              onClick={onCheckConnection}
            >
              <Activity size={14} aria-hidden="true" />
              {t('plugins.connectionCheck')}
            </Button>
          ) : null}

          {isDeepLink ? (
            isConnected ? (
              <Button
                variant="ghost"
                size="compact"
                danger
                aria-label={`${t('plugins.disconnect')} ${plugin.name}`}
                onClick={onDisconnect}
              >
                <LogOut aria-hidden="true" />
                {t('plugins.disconnect')}
              </Button>
            ) : (
              <Button
                size="compact"
                aria-label={`${isNotConnected ? t('plugins.connect') : t('plugins.reconnect')} ${plugin.name}`}
                onClick={onConnect}
              >
                <LogIn aria-hidden="true" />
                {isNotConnected ? t('plugins.connect') : t('plugins.reconnect')}
              </Button>
            )
          ) : null}
        </div>
      }
    >
      {plugin.connectionCheckSupported && !connectionCheckAvailable ? (
        <p
          className="inline-hint"
          role="status"
          data-testid={`plugin-check-blocked-${plugin.pluginId}`}
        >
          {t('plugins.connectionCheck.blockedHint')}
        </p>
      ) : null}
    </ResourceCard>
  )
}
