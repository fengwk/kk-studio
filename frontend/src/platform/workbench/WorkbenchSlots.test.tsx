import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { setLocale } from '@/shared/i18n'

describe('NavigationSlot', () => {
  it('renders all AI secondary navigation labels in order and switches English live', () => {
    const host = new ExtensionHost()
    host.register({
      id: 'ai',
      navigation: [
        { id: 'ai.nav.chats', label: 'Chat', labelKey: 'ai.nav.chats', path: 'chats' },
        { id: 'ai.nav.agents', label: 'Agent', labelKey: 'ai.nav.agents', path: 'agents' },
        { id: 'ai.nav.models', label: 'Model', labelKey: 'ai.nav.models', path: 'models' },
        { id: 'ai.nav.providers', label: 'Provider', labelKey: 'ai.nav.providers', path: 'providers' },
        {
          id: 'ai.nav.environments',
          label: 'Environment',
          labelKey: 'ai.nav.environments',
          path: 'environments',
        },
        { id: 'ai.nav.settings', label: 'Setting', labelKey: 'ai.nav.setting', path: 'settings' },
      ],
    })

    act(() => {
      setLocale('zh-CN')
    })
    render(
      <ExtensionHostProvider host={host}>
        <MemoryRouter initialEntries={['/chats']}>
          <NavigationSlot />
        </MemoryRouter>
      </ExtensionHostProvider>,
    )

    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      '对话',
      '代理',
      '模型',
      '提供商',
      '环境',
      '设置',
    ])

    act(() => {
      setLocale('en-US')
    })
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Chat',
      'Agent',
      'Model',
      'Provider',
      'Environment',
      'Setting',
    ])
  })
})
