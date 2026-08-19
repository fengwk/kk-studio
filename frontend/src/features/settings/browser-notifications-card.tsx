import { useCallback, useState } from 'react'
import { useBrowserPreferences } from '@/features/settings/browser-preferences'
import {
  browserNotificationPermission,
  requestBrowserNotificationPermission,
  type BrowserNotificationPermission,
} from '@/features/ai/runtime/thread-notifications'
import { useI18n } from '@/shared/i18n'

/**
 * 浏览器通知偏好卡（本地存储，非 server settings）。
 *
 * 开关遵循 permission 事实：
 * - `default` 启用时调用浏览器请求，仅 granted 后才写入 enabled=true；
 * - `denied` / `unsupported` 只读引导，不能伪装启用（开关禁用且不写存储）；
 * - checked 只可能在 `granted && notificationsEnabled` 时为 true；即使存储残留
 *   enabled=true 而当前 permission 为 `default`，UI 仍显示未启用，点击走请求权限路径；
 * - 状态文案固定为 `状态：已关闭 · 浏览器权限：已允许`；只有真正 enabled 才高亮绿色。
 */
export function BrowserNotificationsCard() {
  const { t } = useI18n()
  const { notificationsEnabled, setNotificationsEnabled } = useBrowserPreferences()
  const [permission, setPermission] = useState<BrowserNotificationPermission>(
    browserNotificationPermission,
  )
  const canEnable = permission === 'granted' || permission === 'default'
  const enabled = permission === 'granted' && notificationsEnabled
  const status = enabled ? 'enabled' : 'disabled'

  const handleToggle = useCallback(
    async (next: boolean) => {
      if (!next) {
        setNotificationsEnabled(false)
        return
      }
      const current = browserNotificationPermission()
      setPermission(current)
      if (current === 'granted') {
        setNotificationsEnabled(true)
        return
      }
      if (current === 'default') {
        const result = await requestBrowserNotificationPermission()
        setPermission(result)
        if (result === 'granted') {
          setNotificationsEnabled(true)
        }
      }
      // denied / unsupported：保留只读引导，绝不写入 enabled=true。
    },
    [setNotificationsEnabled],
  )

  return (
    <section className="settings-card" aria-labelledby="settings-notifications-title">
      <header className="settings-card-header">
        <h2 id="settings-notifications-title">{t('settings.notifications.title')}</h2>
        <p>{t('settings.notifications.description')}</p>
      </header>
      <div className="settings-row">
        <div className="settings-row-text">
          <strong>{t('settings.notifications.enabled')}</strong>
          <span className="settings-notification-facts">
            <span>
              {t('settings.notifications.statusLabel')}:{' '}
              <span className="settings-enabled-value" data-enabled={enabled}>
                {t(`settings.notifications.status.${status}`)}
              </span>
            </span>
            <span aria-hidden="true">·</span>
            <span>
              {t('settings.notifications.browserPermissionLabel')}:{' '}
              <span className="settings-permission-value" data-permission={permission}>
                {t(`settings.notifications.permission.${permission}`)}
              </span>
            </span>
          </span>
        </div>
        <button
          type="button"
          role="switch"
          className="settings-switch"
          aria-checked={enabled}
          aria-label={t('settings.notifications.enabled')}
          disabled={!canEnable}
          onClick={() => {
            void handleToggle(!enabled)
          }}
        >
          <span className="settings-switch-thumb" aria-hidden="true" />
        </button>
      </div>
      {permission === 'denied' ? (
        <p className="settings-hint danger" role="status">
          {t('settings.notifications.deniedHint')}
        </p>
      ) : null}
      {permission === 'unsupported' ? (
        <p className="settings-hint" role="status">
          {t('settings.notifications.unsupportedHint')}
        </p>
      ) : null}
      {permission === 'default' ? (
        <p className="settings-hint">{t('settings.notifications.defaultHint')}</p>
      ) : null}
    </section>
  )
}
