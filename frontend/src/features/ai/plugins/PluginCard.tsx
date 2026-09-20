import { AlertTriangle, Key, LogIn, LogOut, Plug } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import type { PluginDTO, PluginStatus } from '@/shared/api/contracts/ai-plugin'

function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  try {
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) {
      return value
    }
    return date.toLocaleString()
  } catch {
    return value
  }
}

function statusPillClass(status: PluginStatus): string {
  switch (status) {
    case 'CONNECTED':
      return 'status-pill is-ready'
    case 'REFRESH_FAILED':
    case 'REFRESH_UNCERTAIN':
      return 'status-pill is-warning'
    case 'REAUTH_REQUIRED':
    case 'KEY_UNAVAILABLE':
      return 'status-pill is-error'
    case 'NOT_CONNECTED':
    default:
      return 'status-pill is-offline'
  }
}

export function PluginCard({
  plugin,
  onConnect,
  onDisconnect,
}: {
  plugin: PluginDTO
  onConnect: () => void
  onDisconnect: () => void
}) {
  const { t } = useI18n()
  const isConnected = plugin.status === 'CONNECTED'
  const isKeyUnavailable = plugin.status === 'KEY_UNAVAILABLE'
  const isNotConnected = plugin.status === 'NOT_CONNECTED'

  return (
    <article
      className="info-card plugin-card"
      data-testid={`plugin-card-${plugin.pluginId}`}
    >
      <div className="chat-card-head">
        <div className="lead">
          <span className="card-glyph" aria-hidden="true">
            <Plug />
          </span>
          <div className="text-content">
            <h3 title={plugin.name}>{plugin.name}</h3>
            <p title={plugin.pluginId}>
              <code>{plugin.pluginId}</code>
            </p>
          </div>
        </div>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          <span className="status-pill" style={{ opacity: 0.8 }}>
            v{plugin.version}
          </span>
          <span
            className={statusPillClass(plugin.status)}
            data-testid={`plugin-status-${plugin.pluginId}`}
          >
            {t(`plugins.status.${plugin.status}`) || plugin.status}
          </span>
        </div>
      </div>

      <div className="meta-block">
        <div className="meta-row">
          <span className="lbl">{t('plugins.region')}</span>
          <span className="val">{plugin.region || '—'}</span>
        </div>
        <div className="meta-row">
          <span className="lbl">{t('plugins.tokenExpiresAt')}</span>
          <span className="val">{formatDateTime(plugin.expiresAt)}</span>
        </div>
        <div className="meta-row">
          <span className="lbl">{t('plugins.nextRefreshAt')}</span>
          <span className="val">{formatDateTime(plugin.nextRefreshAt)}</span>
        </div>
        <div className="meta-row">
          <span className="lbl">{t('plugins.lastRefreshedAt')}</span>
          <span className="val">{formatDateTime(plugin.lastRefreshedAt)}</span>
        </div>
        {plugin.lastRefreshError ? (
          <div className="meta-row" role="alert">
            <span className="lbl" style={{ color: 'var(--color-danger, #ef4444)' }}>
              {t('plugins.lastRefreshError')}
            </span>
            <span className="val" style={{ color: 'var(--color-danger, #ef4444)' }}>
              {plugin.lastRefreshError}
            </span>
          </div>
        ) : null}

        {plugin.status === 'REFRESH_UNCERTAIN' ? (
          <div
            className="inline-hint"
            role="status"
            style={{ color: 'var(--color-warning, #f59e0b)', marginTop: '6px' }}
          >
            <AlertTriangle size={14} style={{ display: 'inline', marginRight: '4px' }} />
            {t('plugins.status.REFRESH_UNCERTAIN')}
          </div>
        ) : null}

        {isKeyUnavailable ? (
          <div
            className="inline-hint"
            role="status"
            style={{ color: 'var(--color-danger, #ef4444)', marginTop: '6px' }}
          >
            <Key size={14} style={{ display: 'inline', marginRight: '4px' }} />
            {t('plugins.connectDialog.keyUnavailableHint')}
          </div>
        ) : null}
      </div>

      <div className="chat-card-foot" style={{ justifyContent: 'flex-end' }}>
        {isConnected ? (
          <button
            type="button"
            className="action-enter-btn danger"
            aria-label={`${t('plugins.disconnect')} ${plugin.name}`}
            disabled={isKeyUnavailable}
            onClick={onDisconnect}
          >
            <LogOut aria-hidden="true" />
            {t('plugins.disconnect')}
          </button>
        ) : (
          <button
            type="button"
            className="action-enter-btn"
            style={{ color: 'var(--color-primary, #3b82f6)' }}
            aria-label={`${isNotConnected ? t('plugins.connect') : t('plugins.reconnect')} ${plugin.name}`}
            disabled={isKeyUnavailable}
            onClick={onConnect}
          >
            <LogIn aria-hidden="true" />
            {isNotConnected ? t('plugins.connect') : t('plugins.reconnect')}
          </button>
        )}
      </div>
    </article>
  )
}
