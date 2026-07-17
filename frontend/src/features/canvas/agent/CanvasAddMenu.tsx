import { useEffect, useRef, type KeyboardEvent } from 'react'
import { ADD_MENU_ITEMS } from '@/features/canvas/data'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'

/** Upward add menu with keyboard navigation, composed by the agent dock. */
export function CanvasAddMenu({ menuId }: { menuId: string }) {
  const {
    state,
    closeAddMenu,
    setAddMenuIndex,
    handleAddAction,
  } = useCanvasRuntime()

  const menuRef = useRef<HTMLDivElement>(null)
  const menuButtons = ADD_MENU_ITEMS

  useEffect(() => {
    if (!state.addMenuOpen || !menuRef.current) {
      return
    }
    const action = menuButtons[state.addMenuIndex]?.action
    if (!action) {
      return
    }
    const button = menuRef.current.querySelector<HTMLButtonElement>(`[data-add-action="${action}"]`)
    button?.focus()
  }, [state.addMenuOpen, state.addMenuIndex, menuButtons])

  function handleMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const count = menuButtons.length
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setAddMenuIndex((state.addMenuIndex + 1) % count)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setAddMenuIndex((state.addMenuIndex - 1 + count) % count)
    } else if (event.key === 'Home') {
      event.preventDefault()
      setAddMenuIndex(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      setAddMenuIndex(count - 1)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      closeAddMenu(true)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      const item = menuButtons[state.addMenuIndex]
      if (item) {
        handleAddAction(item.action)
      }
    }
  }

  return (
    <div
      className="add-menu"
      id={menuId}
      ref={menuRef}
      hidden={!state.addMenuOpen}
      inert={!state.addMenuOpen}
      aria-hidden={!state.addMenuOpen}
      role="menu"
      aria-label="添加内容"
      onKeyDown={handleMenuKeyDown}
    >
      {menuButtons.map((item, index) => (
        <button
          key={item.action}
          type="button"
          role="menuitem"
          data-add-action={item.action}
          tabIndex={state.addMenuOpen && index === state.addMenuIndex ? 0 : -1}
          onClick={() => handleAddAction(item.action)}
          onMouseEnter={() => setAddMenuIndex(index)}
        >
          <span className="add-menu-icon">{item.icon}</span>
          <span>{item.label}</span>
        </button>
      ))}
    </div>
  )
}
