import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ChatSidebar } from '@/features/ai/ChatPanelParts'

describe('ChatSidebar', () => {
  it('lists Session Threads at their explicit Session routes', () => {
    render(<MemoryRouter><ChatSidebar threads={[thread('other'), thread('main')]} activeThreadId="main" sessionId="s1" mainThreadId="main" title="Main" onBack={vi.fn()} /></MemoryRouter>)
    expect(screen.getByRole('link', { name: 'Main' })).toHaveAttribute('href', '/sessions/s1/threads/main')
    expect(screen.getByRole('link', { name: 'other' })).toHaveAttribute('href', '/sessions/s1/threads/other')
    expect(screen.queryByText('显示范围')).not.toBeInTheDocument()
  })
})

function thread(threadId: string) { return { threadId, sessionId: 's1', sessionTitle: 'S', headEntryId: null, status: 'IDLE' as const, inputSequence: '0', processing: false, createTime: null, updateTime: null } }
