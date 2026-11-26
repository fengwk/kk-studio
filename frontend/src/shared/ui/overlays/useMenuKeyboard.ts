import { useEffect, useRef, type KeyboardEvent } from 'react'

function enabledItems(menu: HTMLElement | null) {
  return Array.from(menu?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])
}

export function useMenuKeyboard(open: boolean, close: (key: 'Escape' | 'Tab', menu: HTMLDivElement) => void) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const items = enabledItems(ref.current)
    const initial = items.find((item) => item.getAttribute('aria-current') === 'true') ?? items[0] ?? ref.current
    initial?.focus()
  }, [open])

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.defaultPrevented || event.nativeEvent.isComposing) return
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      close('Escape', event.currentTarget)
      return
    }
    if (event.key === 'Tab') {
      close('Tab', event.currentTarget)
      return
    }
    if (event.target instanceof HTMLInputElement || event.altKey || event.ctrlKey || event.metaKey) return

    const rows = enabledItems(ref.current)
    if (rows.length === 0) return
    const current = rows.findIndex((row) => row === document.activeElement)
    let next: HTMLButtonElement | undefined
    if (event.key === 'Home') next = rows[0]
    else if (event.key === 'End') next = rows.at(-1)
    else if (event.key === 'ArrowDown') next = rows[(current + 1) % rows.length]
    else if (event.key === 'ArrowUp') next = rows[(Math.max(0, current) - 1 + rows.length) % rows.length]
    else if (event.key.length === 1 && event.key !== ' ') {
      const ordered = [...rows.slice(current + 1), ...rows.slice(0, current + 1)]
      next = ordered.find((row) => row.textContent?.trim().toLocaleLowerCase().startsWith(event.key.toLocaleLowerCase()))
    }
    if (next) {
      event.preventDefault()
      next.focus()
    }
  }

  return { ref, onKeyDown }
}
