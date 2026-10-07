import { Key, LogIn, LogOut, Plug } from 'lucide-react'
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

/**
 * 状态 pill 只用设计系统已定义的变体：ok=is-ready、warning=is-pending、error=is-failed。
 * 不在卡片里发明新的皮肤类，颜色语义由 .status-pill 变体统一决定。
 */
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

/**
 * 插件资源卡：外观全部来自共享 ResourceCard；安装/连接状态、版本与错误诊断由本 feature 提供，
 * 连接/断开是卡片动作区里的独立操作，不嵌套按钮。
 */
export function PluginCard({
  plugin,
  onConnect,
  onDisconnect,
}: {
  plugin: PluginDTO
  onConnect: () => void
  onDisconnect: () => void
}) {
  const { t, locale } = useI18n()
  const isConnected = plugin.status === 'CONNECTED'
  const isKeyUnavailable = plugin.status === 'KEY_UNAVAILABLE'
  const isNotConnected = plugin.status === 'NOT_CONNECTED'

  const rows: ResourceCardMetaRow[] = [
    [t('plugins.region'), plugin.region || '—'],
    [t('plugins.tokenExpiresAt'), formatDateTime(plugin.expiresAt, locale)],
    [t('plugins.nextRefreshAt'), formatDateTime(plugin.nextRefreshAt, locale)],
    [t('plugins.lastRefreshedAt'), formatDateTime(plugin.lastRefreshedAt, locale)],
  ]
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
          <span className="status-pill is-neutral">v{plugin.version}</span>
          <span
            className={statusPillClass(plugin.status)}
            data-testid={`plugin-status-${plugin.pluginId}`}
          >
            {t(`plugins.status.${plugin.status}`) || plugin.status}
          </span>
        </>
      }
      meta={rows}
      actions={
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
