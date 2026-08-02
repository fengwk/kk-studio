import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ChatCard } from '@/features/ai/chat/ChatCard'

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

  it('uses the Chat id when the title is absent and marks missing default agent', () => {
    render(
      <MemoryRouter>
        <ChatCard chat={{ ...chat(), title: null, agentName: 'missing', environmentName: null }} agents={[]} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('button', { name: '进入 Chat chat-1' })).toBeInTheDocument()
    expect(screen.getByText('missing')).toBeInTheDocument()
  })
})

function Location() {
  return <output data-testid="location">{useLocation().pathname}</output>
}

function chat() {
  return {
    id: 'chat-1',
    title: 'Draft',
     agentName: 'missing',
    environmentName: null,
    version: '1',
    createTime: null,
    updateTime: null,
  }
}
