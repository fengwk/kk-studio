import userEvent from '@testing-library/user-event'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AppShell } from '@/platform/shell/AppShell'
import { setLocale } from '@/shared/i18n'

describe('AppShell locale selector', () => {
  it('switches the live selector between English and 中文', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()

    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AppShell>
          <div>Content</div>
        </AppShell>
      </MemoryRouter>,
    )

    expect(screen.getAllByRole('button', { name: 'English' })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: '中文' })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: '中文' }).every((button) => button.getAttribute('aria-pressed') === 'true')).toBe(true)

    await user.click(screen.getAllByRole('button', { name: 'English' })[0]!)

    expect(document.documentElement.lang).toBe('en-US')
    expect(screen.getAllByRole('button', { name: 'English' }).every((button) => button.getAttribute('aria-pressed') === 'true')).toBe(true)
    expect(screen.getByRole('link', { name: 'AI' })).toBeInTheDocument()
  })
})
