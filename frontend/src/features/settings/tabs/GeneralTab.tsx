import { useI18n } from '@/shared/i18n'
import { KeyboardShortcutList } from '@/shared/shortcuts/KeyboardShortcutList'
import { BrowserNotificationsCard } from '@/features/settings/browser-notifications-card'

/**
 * General tab：浏览器本地偏好（通知 + 只读快捷键目录）。
 * 不触达 server system settings 聚合。
 */
export function GeneralTab() {
  const { t } = useI18n()
  return (
    <div className="settings-section-stack">
      <BrowserNotificationsCard />
      <section className="settings-card" aria-labelledby="settings-shortcuts-title">
        <header className="settings-card-header">
          <h2 id="settings-shortcuts-title">{t('settings.shortcuts.title')}</h2>
          <p>{t('settings.shortcuts.description')}</p>
        </header>
        <KeyboardShortcutList />
      </section>
    </div>
  )
}
