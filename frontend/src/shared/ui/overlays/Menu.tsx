import { useEffect, useRef, type KeyboardEvent } from 'react'
import './Menu.css'

export interface MenuItem {
  label: string
  onSelect: () => void
  disabled?: boolean
}

interface MenuProps {
  items: MenuItem[]
  onClose: () => void
  returnFocusTo?: HTMLElement | null
}

function enabledItems(menu: HTMLElement | null) {
  return Array.from(menu?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])
}

export function Menu({ items, onClose, returnFocusTo }: MenuProps) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const first = enabledItems(ref.current)[0] ?? ref.current
    first?.focus()
  }, [])

  function close() {
    returnFocusTo?.focus()
    onClose()
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      close()
      return
    }
    if (event.key === 'Tab') {
      close()
      return
    }

    const rows = enabledItems(ref.current)
    if (rows.length === 0) return
    const current = rows.findIndex((row) => row === document.activeElement)
    let next: HTMLButtonElement | undefined
    if (event.key === 'Home') next = rows[0]
    else if (event.key === 'End') next = rows.at(-1)
    else if (event.key === 'ArrowDown') next = rows[(current + 1) % rows.length]
    else if (event.key === 'ArrowUp') next = rows[(Math.max(0, current) - 1 + rows.length) % rows.length]
    else if (event.key.length === 1 && event.key !== ' ' && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const ordered = [...rows.slice(current + 1), ...rows.slice(0, current + 1)]
      next = ordered.find((row) => row.textContent?.trim().toLocaleLowerCase().startsWith(event.key.toLocaleLowerCase()))
    }
    if (next) {
      event.preventDefault()
      next.focus()
    }
  }

  return (
    <div ref={ref} className="ui-menu" role="menu" tabIndex={-1} onKeyDown={onKeyDown}>
      {items.map((item) => (
        <button key={item.label} type="button" role="menuitem" tabIndex={-1} data-ui-focus="row"
          disabled={item.disabled} onClick={() => { close(); item.onSelect() }}>
          {item.label}
        </button>
      ))}
    </div>
  )
}
