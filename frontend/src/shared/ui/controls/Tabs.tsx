import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react'
import './controls.css'

export interface TabItem {
  id: string
  label: ReactNode
  disabled?: boolean
}

export interface TabsProps {
  tabs: TabItem[]
  /** 当前选中的 tab id（受控）：只有它本身有效时才呈现为选中并接线 tabpanel。 */
  activeId: string
  onChange: (id: string) => void
  /** tablist 的无障碍名称。 */
  ariaLabel: string
  className?: string
  panelClassName?: string
  /** 当前 tab 的内容；面板 role/aria-labelledby 由 Tabs 统一接线。 */
  children: ReactNode
}

const NAVIGATION_KEYS = ['ArrowRight', 'ArrowLeft', 'Home', 'End']

/**
 * 无障碍 Tabs：tablist/tab/tabpanel 语义、roving tabindex、方向键/Home/End 导航与
 * 选中态自动跟随焦点。选中状态完全由调用方持有，不缓存任何业务状态。
 *
 * 状态契约：activeId 指向不可用（不存在或 disabled）的 tab 时，不伪造选中，
 * tablist 不标选任何一项，children 以中性区域呈现；tabs 为空时不渲染 tablist。
 */
export function Tabs({
  tabs,
  activeId,
  onChange,
  ariaLabel,
  className,
  panelClassName,
  children,
}: TabsProps) {
  const baseId = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])
  const enabledIndexes = tabs
    .map((tab, index) => (tab.disabled ? -1 : index))
    .filter((index) => index >= 0)
  const activeTab = tabs.find((tab) => tab.id === activeId && !tab.disabled)
  const rovingTab = activeTab ?? enabledIndexes.map((index) => tabs[index]).find(Boolean)
  const tabId = (id: string) => `ui-tab-${baseId}-${id}`
  const panelId = (id: string) => `ui-tabpanel-${baseId}-${id}`
  const panelClasses = ['ui-tab-panel', panelClassName].filter(Boolean).join(' ')

  function activate(index: number) {
    const tab = tabs[index]
    if (!tab || tab.disabled) {
      return
    }
    onChange(tab.id)
    tabRefs.current[index]?.focus()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    // 输入法组合中的按键与已被上层处理的按键不归 Tabs；其余按键也不拦截。
    // React 的合成事件不透出 isComposing，需读原生事件。
    if (event.defaultPrevented || event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (!NAVIGATION_KEYS.includes(event.key) || enabledIndexes.length === 0) {
      return
    }
    const currentIndex = tabs.findIndex((tab) => tab.id === rovingTab?.id)
    const position = Math.max(0, enabledIndexes.indexOf(currentIndex))
    if (event.key === 'ArrowRight') {
      event.preventDefault()
      activate(enabledIndexes[(position + 1) % enabledIndexes.length])
      return
    }
    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      activate(enabledIndexes[(position - 1 + enabledIndexes.length) % enabledIndexes.length])
      return
    }
    event.preventDefault()
    activate(event.key === 'Home' ? enabledIndexes[0] : enabledIndexes[enabledIndexes.length - 1])
  }

  return (
    <div className={className}>
      {tabs.length > 0 ? (
        <div className="ui-tabs" role="tablist" aria-label={ariaLabel} onKeyDown={handleKeyDown}>
          {tabs.map((tab, index) => {
            const selected = tab.id === activeTab?.id
            return (
              <button
                key={tab.id}
                ref={(element) => {
                  tabRefs.current[index] = element
                }}
                id={tabId(tab.id)}
                type="button"
                role="tab"
                className="ui-tab"
                disabled={tab.disabled}
                aria-selected={selected}
                aria-controls={panelId(tab.id)}
                tabIndex={tab.id === rovingTab?.id ? 0 : -1}
                onClick={() => {
                  if (tab.id !== activeTab?.id) {
                    onChange(tab.id)
                  }
                }}
              >
                {tab.label}
              </button>
            )
          })}
        </div>
      ) : null}
      {activeTab ? (
        <div
          className={panelClasses}
          id={panelId(activeTab.id)}
          role="tabpanel"
          aria-labelledby={tabId(activeTab.id)}
        >
          {children}
        </div>
      ) : (
        // 没有有效选中 tab：内容仍是调用方的真实渲染结果，但不冒充任何 tab 的面板。
        <div className={panelClasses} data-tab-state="no-active-tab">
          {children}
        </div>
      )}
    </div>
  )
}
