import userEvent from '@testing-library/user-event'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AppShell } from '@/platform/shell/AppShell'
import { setLocale } from '@/shared/i18n'

describe('AppShell locale selector', () => {
  it('keeps both responsive dropdowns synchronized and supports keyboard controls', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()

    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AppShell>
          <div>Content</div>
        </AppShell>
      </MemoryRouter>,
    )

    const chineseTriggers = screen.getAllByRole('button', { name: '语言: 中文' })
    expect(chineseTriggers).toHaveLength(2)
    expect(chineseTriggers.map((trigger) => trigger.querySelector('.locale-selector-current')?.textContent)).toEqual([
      '中文',
      '中文',
    ])
    expect(chineseTriggers.every((trigger) => trigger.getAttribute('aria-expanded') === 'false')).toBe(true)

    await user.click(chineseTriggers[0]!)

    expect(screen.getByRole('listbox', { name: '语言' })).toBeInTheDocument()
    expect(screen.getAllByRole('option')).toHaveLength(2)
    expect(screen.getByRole('option', { name: 'English', exact: true })).toHaveAttribute(
      'aria-selected',
      'false',
    )
    expect(chineseTriggers[0]).toHaveAttribute('aria-expanded', 'true')
    expect(chineseTriggers[1]).toHaveAttribute('aria-expanded', 'false')

    // 选中的「中文」选项在打开时聚焦；ArrowUp 移到 English，Enter 选中它。
    await user.keyboard('{ArrowUp}')
    await user.keyboard('{Enter}')

    expect(document.documentElement.lang).toBe('en-US')
    const englishTriggers = screen.getAllByRole('button', { name: 'Language: English' })
    expect(englishTriggers).toHaveLength(2)
    expect(englishTriggers.map((trigger) => trigger.querySelector('.locale-selector-current')?.textContent)).toEqual([
      'English',
      'English',
    ])
    expect(localStorage.getItem('kk-studio.locale')).toBe('en-US')
    expect(screen.getByRole('link', { name: 'AI' })).toBeInTheDocument()

    await user.click(englishTriggers[0]!)
    expect(screen.getByRole('listbox', { name: 'Language' })).toBeInTheDocument()
    await user.click(screen.getByText('Content'))
    expect(screen.queryByRole('listbox', { name: 'Language' })).not.toBeInTheDocument()

    await user.click(englishTriggers[1]!)
    expect(screen.getByRole('listbox', { name: 'Language' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox', { name: 'Language' })).not.toBeInTheDocument()
    expect(document.activeElement).toBe(englishTriggers[1])
  })
})
