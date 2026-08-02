import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ChatCard } from '@/features/ai/chat/ChatCard'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'

describe('ChatCard', () => {
  it('opens the Chat workspace route', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <ChatCard chat={chat()} agents={[]} />
        <Location />
      </MemoryRouter>,
    )
    await user.click(screen.getByRole('button', { name: '进入 Chat Draft' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/chats/chat-1')
  })

  it('uses the Chat id when the title is absent and marks a missing Agent', () => {
    render(
      <MemoryRouter>
        <ChatCard chat={{ ...chat(), title: null, agentName: 'missing', environmentName: null }} agents={[]} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('button', { name: '进入 Chat chat-1' })).toBeInTheDocument()
    expect(screen.getByText('missing')).toBeInTheDocument()
    expect(screen.getByText('（已删除/缺失）')).toBeInTheDocument()
    expect(screen.getByLabelText('missing （已删除/缺失）')).toHaveAttribute(
      'aria-disabled',
      'true',
    )
  })

  it('does not mark a valid Agent as unavailable', () => {
    render(
      <MemoryRouter>
        <ChatCard
          chat={{ ...chat(), agentName: 'assistant' }}
          agents={[{ ...agent(), name: 'assistant' }]}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText('assistant')).toBeInTheDocument()
    expect(screen.queryByText('（已删除/缺失）')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('assistant （已删除/缺失）')).not.toBeInTheDocument()
  })
})

function Location() {
  return <output data-testid="location">{useLocation().pathname}</output>
}

function agent() {
  return {
    name: 'missing',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    config: { tools: [], skills: [] },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function chat() {
  return {
    id: 'chat-1',
    title: 'Draft',
    agentName: 'missing',
    environmentName: null,
    yoloEnabled: false,
    version: '1',
    createTime: null,
    updateTime: null,
  } satisfies ChatDTO
}
