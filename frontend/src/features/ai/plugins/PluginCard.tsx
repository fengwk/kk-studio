import { Key, LogIn, LogOut, Plug, Settings } from 'lucide-react'
import { useI18n, type AppLocale } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ResourceCard, type ResourceCardMetaRow } from '@/shared/ui/cards/ResourceCard'
import type { PluginDTO, PluginStatus } from '@/shared/api/contracts/ai-plugin'

function formatDateTime(value: string | null | undefined, locale: AppLocale): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString(locale)
}

function statusPillClass(status: PluginStatus): string {
  switch (status) {
    case 'CONNECTED':
      return 'status-pill is-ready'
    case 'REFRESH_FAILED':
    case 'REFRESH_UNCERTAIN':
      return 'status-pill is-pending'
    case 'REAUTH_REQUIRED':
    case 'KEY_UNAVAILABLE':
      return 'status-pill is-failed'
    case 'NOT_CONNECTED':
    default:
      return 'status-pill is-offline'
  }
}

export function PluginCard({
  plugin,
  onConfigure,
  onConnect,
  onDisconnect,
}: {
  plugin: PluginDTO
  onConfigure: () => void
  onConnect: () => void
  onDisconnect: () => void
}) {
  const { t, locale } = useI18n()

  const isDeepLink = plugin.authKind?.type === 'DEEP_LINK'
  const isConnected = plugin.status === 'CONNECTED'
  const isKeyUnavailable = plugin.status === 'KEY_UNAVAILABLE'
  const isNotConnected = plugin.status === 'NOT_CONNECTED'

  // 坐标不同或存在 applicationError 即为待应用状态，不能展示为全局已生效
  const isPendingApplication =
    plugin.applicationError !== null ||
    plugin.desiredEpoch !== plugin.appliedEpoch ||
    plugin.desiredRevision !== plugin.appliedRevision

  const activeCapabilities = [
    ...plugin.harnessToolNames,
    ...plugin.canvasFunctionNames,
  ]

  const rows: ResourceCardMetaRow[] = [
    [
      t('plugins.desiredEpoch'),
      `Epoch ${plugin.desiredEpoch} · Rev ${plugin.desiredRevision}`,
    ],
    [
      t('plugins.appliedEpoch'),
      `Epoch ${plugin.appliedEpoch ?? '—'} · Rev ${plugin.appliedRevision ?? '—'}`,
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
  }

  return (
    <ResourceCard
      className="plugin-card"
      icon={<Plug aria-hidden="true" />}
      title={plugin.name}
      subtitle={plugin.pluginId}
      badge={
        <>
          <span className="status-pill is-neutral">v{plugin.version}</span>
          <span
            className={
              isPendingApplication ? 'status-pill is-pending' : 'status-pill is-ready'
            }
            data-testid={`plugin-app-status-${plugin.pluginId}`}
          >
            {isPendingApplication
              ? t('plugins.applicationStatus.pending')
              : t('plugins.applicationStatus.applied')}
          </span>
          {plugin.authKind && plugin.status ? (
            <span
              className={statusPillClass(plugin.status)}
              data-testid={`plugin-status-${plugin.pluginId}`}
            >
              {t(`plugins.status.${plugin.status}`) || plugin.status}
            </span>
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

          {isDeepLink ? (
            isConnected ? (
              <Button
                variant="ghost"
                size="compact"
                danger
                aria-label={`${t('plugins.disconnect')} ${plugin.name}`}
                disabled={isKeyUnavailable}
                onClick={onDisconnect}
              >
                <LogOut aria-hidden="true" />
                {t('plugins.disconnect')}
              </Button>
            ) : (
              <Button
                size="compact"
                aria-label={`${isNotConnected ? t('plugins.connect') : t('plugins.reconnect')} ${plugin.name}`}
                disabled={isKeyUnavailable}
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
      {isKeyUnavailable ? (
        <p className="inline-hint danger" role="status">
          <Key aria-hidden="true" size={14} /> {t('plugins.connectDialog.keyUnavailableHint')}
        </p>
      ) : null}
    </ResourceCard>
  )
}
