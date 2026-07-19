import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CreateSessionModal } from '@/features/ai/AiConsoleSessionModals'

describe('CreateSessionModal', () => {
  it('does not render while closed and submits a selected Agent Session form when open', async () => {
    const onSubmit = vi.fn((event) => event.preventDefault())
    const { rerender } = render(<CreateSessionModal open={false} agents={[]} selectedAgentId="" title="" pending={false} onClose={vi.fn()} onSelectAgent={vi.fn()} onTitleChange={vi.fn()} onSubmit={onSubmit} />)
    expect(screen.queryByRole('form', { name: '新建 Chat' })).not.toBeInTheDocument()
    const user = userEvent.setup()
    const onClose = vi.fn()
    const onSelectAgent = vi.fn()
    const onTitleChange = vi.fn()
    rerender(<CreateSessionModal open agents={[{ id: 'a1', name: 'Agent', description: null, systemPrompt: null, defaultProviderId: 'p', defaultProviderName: 'p', defaultModelId: 'm', defaultModelName: 'm', defaultVariant: 'v', toolsJson: null, createTime: null, updateTime: null }]} selectedAgentId="a1" title="Draft" pending={false} onClose={onClose} onSelectAgent={onSelectAgent} onTitleChange={onTitleChange} onSubmit={onSubmit} />)
    await user.selectOptions(screen.getByLabelText('Agent'), 'a1')
    await user.clear(screen.getByPlaceholderText('会话标题'))
    await user.type(screen.getByPlaceholderText('会话标题'), 'New title')
    await user.click(screen.getByRole('button', { name: '确认创建' }))
    expect(onSelectAgent).toHaveBeenCalledWith('a1')
    expect(onTitleChange).toHaveBeenCalled()
    expect(onSubmit).toHaveBeenCalled()
  })

  it('disables create when no Agent is selected or creation is pending', () => {
    const props = { agents: [], selectedAgentId: '', title: '', onClose: vi.fn(), onSelectAgent: vi.fn(), onTitleChange: vi.fn(), onSubmit: vi.fn() }
    const { rerender } = render(<CreateSessionModal open {...props} pending={false} />)
    expect(screen.getByRole('button', { name: '确认创建' })).toBeDisabled()
    rerender(<CreateSessionModal open {...props} selectedAgentId="a1" pending />)
    expect(screen.getByRole('button', { name: '确认创建' })).toBeDisabled()
  })
})
