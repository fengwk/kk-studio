import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AiNavigation } from '@/app/ai-console/AiNavigation'
import { setLocale } from '@/shared/i18n'

describe('AiNavigation', () => {
  it('renders all AI secondary navigation links in metadata order and switches language live', () => {
    act(() => {
      setLocale('zh-CN')
    })

    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AiNavigation />
      </MemoryRouter>,
    )

    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      '对话',
      '代理',
      '模型',
      '提供商',
      '技能包',
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

})
