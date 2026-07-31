import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { setLocale } from '@/shared/i18n'

describe('NavigationSlot', () => {
  it('resolves the AI setting label reactively as Setting and 设置', () => {
    const host = new ExtensionHost()
    host.register({
      id: 'ai',
      navigation: [{
        id: 'ai.nav.settings',
        label: '设置',
        labelKey: 'ai.nav.setting',
        path: 'settings',
      }],
    })

    render(
      <ExtensionHostProvider host={host}>
        <MemoryRouter initialEntries={['/settings']}>
          <NavigationSlot />
        </MemoryRouter>
      </ExtensionHostProvider>,
    )

    expect(screen.getByRole('link', { name: '设置' })).toBeInTheDocument()

    act(() => {
      setLocale('en-US')
    })
    expect(screen.getByRole('link', { name: 'Setting' })).toBeInTheDocument()

    act(() => {
      setLocale('zh-CN')
    })
    expect(screen.getByRole('link', { name: '设置' })).toBeInTheDocument()
  })
})
