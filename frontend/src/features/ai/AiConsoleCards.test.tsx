import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { SessionCard } from '@/features/ai/AiConsoleCards'

describe('SessionCard', () => {
  it('opens the Session default route rather than a root Thread route', async () => {
    const user = userEvent.setup()
    render(<MemoryRouter><SessionCard session={session()} /><Location /></MemoryRouter>)
    await user.click(screen.getByRole('button', { name: '进入会话 Draft' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/sessions/session-1')
  })

  it('uses the Session id when the title is absent', () => {
    render(<MemoryRouter><SessionCard session={{ ...session(), title: null }} /></MemoryRouter>)
    expect(screen.getByRole('button', { name: '进入会话 session-1' })).toBeInTheDocument()
  })
})

function Location() {
  return <output data-testid="location">{useLocation().pathname}</output>
}

function session() {
  return {
    sessionId: 'session-1', title: 'Draft', mainThreadId: 'thread-1', rootSessionId: 'session-1',
    parentSessionId: null, parentInvocationId: null, depth: 0, createTime: null, updateTime: null,
  }
}
