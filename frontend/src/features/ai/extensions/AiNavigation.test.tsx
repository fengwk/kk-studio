import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { aiExtension } from '@/features/ai/extensions/ai-extension.definition'
import { AiNavigation } from '@/features/ai/extensions/AiNavigation'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { setLocale } from '@/shared/i18n'

describe('AiNavigation', () => {
  it('renders all AI secondary navigation links in metadata order and switches language live', () => {
    const host = new ExtensionHost()
    host.register(aiExtension)

    act(() => {
      setLocale('zh-CN')
    })

    render(
      <ExtensionHostProvider host={host}>
        <MemoryRouter initialEntries={['/chats']}>
          <AiNavigation />
        </MemoryRouter>
      </ExtensionHostProvider>,
    )

    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      '对话',
      '代理',
      '模型',
      '提供商',
      'Skill Packages',
      '环境',
      'MCP 服务',
    ])

    act(() => {
      setLocale('en-US')
    })

    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Chat',
      'Agent',
      'Model',
      'Provider',
      'Skill Packages',
      'Environment',
      'MCP Server',
    ])
  })

  it('falls back to default aiExtension pages when host is omitted', () => {
    act(() => {
      setLocale('en-US')
    })
    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AiNavigation />
      </MemoryRouter>,
    )
    expect(screen.getAllByRole('link')).toHaveLength(7)
  })
})
