import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react'
import './controls.css'

export interface TabItem {
  id: string
  label: ReactNode
  disabled?: boolean
}

export interface TabsProps {
  tabs: TabItem[]
  /** 当前选中的 tab id（受控）。 */
  activeId: string
  onChange: (id: string) => void
  /** tablist 的无障碍名称。 */
  ariaLabel: string
  className?: string
  panelClassName?: string
  /** 当前 tab 的内容；面板 role/aria-labelledby 由 Tabs 统一接线。 */
  children: ReactNode
}

/**
 * 无障碍 Tabs：tablist/tab/tabpanel 语义、roving tabindex、方向键/Home/End 导航与
 * 选中态自动跟随焦点。选中状态由调用方持有，Tabs 不缓存任何业务状态。
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
  const activeTab = tabs.find((tab) => tab.id === activeId && !tab.disabled) ?? tabs.find((tab) => !tab.disabled)
  const focusableId = activeTab?.id ?? tabs[0]?.id
  const tabId = (id: string) => `ui-tab-${baseId}-${id}`
  const panelId = (id: string) => `ui-tabpanel-${baseId}-${id}`

  function activate(index: number) {
    const tab = tabs[index]
    if (!tab || tab.disabled) {
      return
    }
    onChange(tab.id)
    tabRefs.current[index]?.focus()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const enabledIndexes = tabs.map((tab, index) => (tab.disabled ? -1 : index)).filter((index) => index >= 0)
    if (enabledIndexes.length === 0) {
      return
    }
    const currentIndex = tabs.findIndex((tab) => tab.id === (activeTab?.id ?? ''))
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
    if (event.key === 'Home') {
      event.preventDefault()
      activate(enabledIndexes[0])
      return
    }
    if (event.key === 'End') {
      event.preventDefault()
      activate(enabledIndexes[enabledIndexes.length - 1])
    }
  }

  return (
    <div className={className}>
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
              tabIndex={tab.id === focusableId ? 0 : -1}
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
      <div
        className={['ui-tab-panel', panelClassName].filter(Boolean).join(' ')}
        id={activeTab ? panelId(activeTab.id) : undefined}
        role="tabpanel"
        aria-labelledby={activeTab ? tabId(activeTab.id) : undefined}
      >
        {children}
      </div>
    </div>
  )
}
