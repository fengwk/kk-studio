import {
  SHORTCUT_CATALOG,
  SHORTCUT_SCOPE_ORDER,
  SHORTCUT_SCOPE_TITLE_KEYS,
} from '@/shared/shortcuts/shortcut-catalog'
import { useI18n } from '@/shared/i18n'

/**
 * 只读快捷键目录列表：按 scope 分组展示唯一事实源 {@link SHORTCUT_CATALOG}。
 * 无搜索/选择；Settings 页与 Thread /shortcuts 面板复用同一组件。
 */
export function KeyboardShortcutList() {
  const { t } = useI18n()
  return (
    <ul className="shortcut-list">
      {SHORTCUT_SCOPE_ORDER.map((scope) => (
        <li key={scope} className="shortcut-group">
          <h4 className="shortcut-group-title">{t(SHORTCUT_SCOPE_TITLE_KEYS[scope])}</h4>
          <ul className="shortcut-group-list">
            {SHORTCUT_CATALOG.filter((definition) => definition.scope === scope).map((definition) => (
              <li key={definition.id} className="shortcut-row">
                <span className="shortcut-text">
                  <span className="shortcut-label">{t(definition.labelKey)}</span>
                  <span className="shortcut-description">{t(definition.descriptionKey)}</span>
                </span>
                <kbd className="shortcut-keys">{definition.keys}</kbd>
              </li>
            ))}
          </ul>
        </li>
      ))}
    </ul>
  )
}
