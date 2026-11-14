import { useRef, type KeyboardEvent } from 'react'
import { useI18n } from '@/shared/i18n'
import {
  DEBUG_TAB_LABEL_KEYS,
  DEBUG_VIEW_TABS,
  type DebugViewTab,
} from '@/features/thread/runtime/thread-panel/thread-debug-view'

/**
 * 窄面板下的 WAI-ARIA Tab 导航（宽面板不渲染 tablist）。
 *
 * 页签自带 roving tabIndex：只有激活页签进入 Tab 序列；ArrowLeft/ArrowRight 环绕、
 * Home/End 跳端，并把焦点移到新激活的页签。页签与面板 ID 由调用方的 useId 派生，
 * 避免多 pane split 时冲突。
 */
export function ThreadDebugTabs({
  baseId,
  activeTab,
  onSelect,
}: {
  baseId: string
  activeTab: DebugViewTab
  onSelect: (tab: DebugViewTab) => void
}) {
  const { t } = useI18n()
  const buttonRefs = useRef<Record<DebugViewTab, HTMLButtonElement | null>>({
    preview: null,
    events: null,
    detail: null,
  })

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, currentTab: DebugViewTab) {
    const nextTab = nextTabOf(currentTab, event.key)
    if (nextTab == null) {
      return
    }
    event.preventDefault()
    onSelect(nextTab)
    buttonRefs.current[nextTab]?.focus()
  }

  return (
    <div className="thread-debug-tabs" role="tablist" aria-label={t('ai.runtime.debug.tabsAriaLabel')}>
      {DEBUG_VIEW_TABS.map((tab) => (
        <button
          key={tab}
          ref={(el) => {
            buttonRefs.current[tab] = el
          }}
          type="button"
          role="tab"
          id={`${baseId}-tab-${tab}`}
          aria-controls={`${baseId}-panel-${tab}`}
          aria-selected={activeTab === tab}
          tabIndex={activeTab === tab ? 0 : -1}
          className={`thread-debug-tab ${activeTab === tab ? 'active' : ''}`}
          onClick={() => onSelect(tab)}
          onKeyDown={(event) => handleKeyDown(event, tab)}
        >
          {t(DEBUG_TAB_LABEL_KEYS[tab])}
        </button>
      ))}
    </div>
  )
}

/** roving 导航目标：ArrowLeft/ArrowRight 环绕，Home/End 跳端，其余按键不改页签。 */
function nextTabOf(currentTab: DebugViewTab, key: string): DebugViewTab | null {
  const currentIndex = DEBUG_VIEW_TABS.indexOf(currentTab)
  const lastIndex = DEBUG_VIEW_TABS.length - 1
  switch (key) {
    case 'ArrowRight':
      return DEBUG_VIEW_TABS[(currentIndex + 1) % DEBUG_VIEW_TABS.length]!
    case 'ArrowLeft':
      return DEBUG_VIEW_TABS[(currentIndex - 1 + DEBUG_VIEW_TABS.length) % DEBUG_VIEW_TABS.length]!
    case 'Home':
      return DEBUG_VIEW_TABS[0]!
    case 'End':
      return DEBUG_VIEW_TABS[lastIndex]!
    default:
      return null
  }
}
