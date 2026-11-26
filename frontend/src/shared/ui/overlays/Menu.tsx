import { useMenuKeyboard } from './useMenuKeyboard'
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
  placement?: 'above-start'
}

export function Menu({ items, onClose, returnFocusTo, placement }: MenuProps) {
  function close() {
    returnFocusTo?.focus()
    onClose()
  }
  const keyboard = useMenuKeyboard(true, close)

  return (
    <div {...keyboard} className="ui-menu" data-placement={placement} role="menu" tabIndex={-1}>
      {items.map((item) => (
        <button key={item.label} type="button" role="menuitem" tabIndex={-1} data-ui-focus="row"
          disabled={item.disabled} onClick={() => { close(); item.onSelect() }}>
          {item.label}
        </button>
      ))}
    </div>
  )
}
