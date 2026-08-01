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

    const selectors = screen.getAllByRole('combobox', { name: '语言' })
    expect(selectors).toHaveLength(2)
    expect(selectors.every((selector) => (selector as HTMLSelectElement).value === 'zh-CN')).toBe(true)

    await user.selectOptions(selectors[1]!, 'en-US')

    expect(document.documentElement.lang).toBe('en-US')
    const englishSelectors = screen.getAllByRole('combobox', { name: 'Language' })
    expect(englishSelectors).toHaveLength(2)
    expect(englishSelectors.every((selector) => (selector as HTMLSelectElement).value === 'en-US')).toBe(true)
    expect(localStorage.getItem('kk-studio.locale')).toBe('en-US')
    expect(screen.getByRole('link', { name: 'AI' })).toBeInTheDocument()
  })
})
