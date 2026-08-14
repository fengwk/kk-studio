import { useCallback, useState } from 'react'
import { KeyboardShortcutList } from '@/shared/shortcuts/KeyboardShortcutList'
import { useApplicationSettings } from '@/features/settings/application-settings'
import {
  browserNotificationPermission,
  requestBrowserNotificationPermission,
  type BrowserNotificationPermission,
} from '@/features/ai/runtime/thread-notifications'
import { useI18n } from '@/shared/i18n'

/**
 * 全局设置页：Notifications + 只读 Keyboard Shortcuts catalog。
 *
 * Notifications 开关遵循 permission 事实：
 * - `default` 启用时调用浏览器请求，仅 granted 后才写入 enabled=true；
 * - `denied` / `unsupported` 只读引导，不能伪装启用（开关禁用且不写存储）；
 * - permission 状态在每次点击后重新读取并更新展示。
 */
export function SettingsPage() {
  const { t } = useI18n()
  const { notificationsEnabled, setNotificationsEnabled } = useApplicationSettings()
  const [permission, setPermission] = useState<BrowserNotificationPermission>(
    browserNotificationPermission,
  )
  const canEnable = permission === 'granted' || permission === 'default'
  const enabled = canEnable && notificationsEnabled

  const handleToggle = useCallback(async (next: boolean) => {
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
  }, [setNotificationsEnabled])

  return (
    <section className="screen active">
      <div className="screen-body settings-body">
        <h1 className="settings-title">{t('settings.title')}</h1>

        <section className="settings-card" aria-labelledby="settings-notifications-title">
          <header className="settings-card-header">
            <h2 id="settings-notifications-title">{t('settings.notifications.title')}</h2>
            <p>{t('settings.notifications.description')}</p>
          </header>
          <div className="settings-row">
            <div className="settings-row-text">
              <strong>{t('settings.notifications.enabled')}</strong>
              <span className="settings-permission">
                {t('settings.notifications.permissionLabel')}:{' '}
                <span className="settings-permission-value" data-permission={permission}>
                  {t(`settings.notifications.permission.${permission}`)}
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

        <section className="settings-card" aria-labelledby="settings-shortcuts-title">
          <header className="settings-card-header">
            <h2 id="settings-shortcuts-title">{t('settings.shortcuts.title')}</h2>
            <p>{t('settings.shortcuts.description')}</p>
          </header>
          <KeyboardShortcutList />
        </section>
      </div>
    </section>
  )
}
