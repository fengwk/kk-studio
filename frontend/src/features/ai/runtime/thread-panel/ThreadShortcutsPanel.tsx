import { useEffect, useRef } from 'react'
import { ThreadInteractionPanel } from '@/features/ai/runtime/thread-panel/ThreadInteractionPanel'
import {
  SHORTCUT_CATALOG,
} from '@/features/ai/runtime/thread-panel/shortcut-catalog'
import { useI18n } from '@/shared/i18n'

/**
 * 只读快捷键面板：在所有 Composer 场景可用（Blank/Bound、Chat/Canvas）。
 * Esc 关闭并恢复 Composer 焦点；catalog 是展示数据，不做全局 dispatcher。
 */
export function ThreadShortcutsPanel({ onClose }: { onClose: () => void }) {
  const { t } = useI18n()
  const panelRef = useRef<HTMLElement>(null)
  useEffect(() => {
    // 只读面板没有可聚焦的输入；把焦点挂到 section 自身，使 Esc 可达。
    panelRef.current?.focus({ preventScroll: true })
  }, [])
  return (
    <ThreadInteractionPanel
      title={t('ai.runtime.shortcuts.title')}
      className="thread-shortcuts-panel"
      bodyClassName="thread-shortcuts-body"
      panelRef={panelRef}
      onClose={onClose}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          onClose()
        }
      }}
    >
      <ul className="thread-shortcuts-list">
        {SHORTCUT_CATALOG.map((group) => (
          <li key={group.id} className="thread-shortcuts-group">
            <h4 className="thread-shortcuts-group-title">{t(group.titleKey)}</h4>
            <ul className="thread-shortcuts-group-list">
              {group.entries.map((entry) => (
                <li key={`${group.id}:${entry.keys}`} className="thread-shortcuts-row">
                  <kbd className="thread-shortcuts-keys">{entry.keys}</kbd>
                  <span className="thread-shortcuts-description">{t(entry.descriptionKey)}</span>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </ThreadInteractionPanel>
  )
}
