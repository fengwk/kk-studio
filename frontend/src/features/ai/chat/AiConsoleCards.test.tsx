import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
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

  // 验证 ChatCard 支持编辑和删除动作，与现有资源卡风格和无障碍语义保持一致
  it('supports edit and delete actions with accessible aria labels', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    render(
      <MemoryRouter>
        <ChatCard
          chat={chat()}
          agents={[]}
          onEdit={onEdit}
          onDelete={onDelete}
          deletePending={false}
        />
      </MemoryRouter>,
    )

    const editBtn = screen.getByRole('button', { name: '编辑 Draft' })
    const deleteBtn = screen.getByRole('button', { name: '删除 Draft' })

    expect(editBtn).toBeInTheDocument()
    expect(deleteBtn).toBeInTheDocument()
    expect(deleteBtn).not.toBeDisabled()

    await user.click(editBtn)
    expect(onEdit).toHaveBeenCalledOnce()

    await user.click(deleteBtn)
    expect(onDelete).toHaveBeenCalledOnce()
  })

  // 验证删除处于 pending 状态时禁用删除按钮，防止重复提交
  it('disables delete button when deletePending is true', () => {
    render(
      <MemoryRouter>
        <ChatCard
          chat={chat()}
          agents={[]}
          onEdit={() => undefined}
          onDelete={() => undefined}
          deletePending={true}
        />
      </MemoryRouter>,
    )

    expect(screen.getByRole('button', { name: '删除 Draft' })).toBeDisabled()
  })

  it('uses the Chat id when the title is absent and marks a missing Agent', () => {
    render(
      <MemoryRouter>
        <ChatCard chat={{ ...chat(), title: null, agentName: 'missing' }} agents={[]} />
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

  // 验证 ChatCard 明确展示 Workspace Path 标签与路径，不使用模糊的 Environment 标签误导用户。
  it('renders explicit workspace path label instead of independent environment label', () => {
    render(
      <MemoryRouter>
        <ChatCard
          chat={{ ...chat(), workspacePath: 'projects/frontend-app' }}
          agents={[{ ...agent(), name: 'assistant' }]}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText('工作区路径')).toBeInTheDocument()
    expect(screen.getByText('projects/frontend-app')).toBeInTheDocument()
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
    config: { toolIds: [], skills: [], subagents: [] },
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
    yoloEnabled: false,
    version: '1',
    createTime: null,
    updateTime: null,
  } satisfies ChatDTO
}
