import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { KeyboardShortcutList } from '@/shared/shortcuts/KeyboardShortcutList'
import { SHORTCUT_CATALOG, SHORTCUT_SCOPE_ORDER } from '@/shared/shortcuts/shortcut-catalog'
import { setLocale } from '@/shared/i18n'

describe('KeyboardShortcutList', () => {
  it('groups the single fact source by scope in the frozen order', () => {
    render(<KeyboardShortcutList />)
    const groups = screen.getAllByRole('listitem').filter((item) => item.matches('.shortcut-group'))
    expect(groups.map((group) => group.querySelector('.shortcut-group-title')?.textContent)).toEqual(
      ['应用', '对话', '事件', '画布'],
    )
    expect(groups.map((group) => group.querySelector('.shortcut-group-list')?.children.length)).toEqual(
      SHORTCUT_SCOPE_ORDER.map((scope) =>
        SHORTCUT_CATALOG.filter((definition) => definition.scope === scope).length,
      ),
    )
  })

  it('renders every row with keys, label and description resolved from i18n', () => {
    setLocale('en-US')
    render(<KeyboardShortcutList />)
    const rows = screen.getAllByRole('listitem').filter((item) => item.matches('.shortcut-row'))
    expect(rows.length).toBe(SHORTCUT_CATALOG.length)
    for (const definition of SHORTCUT_CATALOG) {
      const row = rows.find((item) => item.querySelector('.shortcut-keys')?.textContent === definition.keys)!
      expect(row.querySelector('.shortcut-label')?.textContent).not.toMatch(/⟦missing/)
      expect(row.querySelector('.shortcut-description')?.textContent).not.toMatch(/⟦missing/)
      expect(row.querySelector('.shortcut-keys')?.textContent).toBe(definition.keys)
    }
  })

  it('is a pure read-only catalog without search or selection', () => {
    const { container } = render(<KeyboardShortcutList />)
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('option')).not.toBeInTheDocument()
    const list = container.querySelector('.shortcut-list')
    expect(list).not.toBeNull()
    expect(within(list as HTMLElement).queryByRole('button')).not.toBeInTheDocument()
    expect(within(list as HTMLElement).queryByRole('textbox')).not.toBeInTheDocument()
  })
})
