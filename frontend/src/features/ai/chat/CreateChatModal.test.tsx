import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CreateChatModal } from '@/features/ai/chat/CreateChatModal'

const agent = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: {
    tools: [],
    skills: [],
  },
  version: '1',
  createTime: null,
  updateTime: null,
}

describe('CreateChatModal', () => {
  it('renders nothing when closed', () => {
    const { container } = render(
      <CreateChatModal
        open={false}
        agents={[]}
        selectedAgentName=""
        title=""
        pending={false}
        onClose={() => undefined}
        onSelectAgent={() => undefined}
        onTitleChange={() => undefined}
        onSubmit={() => undefined}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('matches resource modal validation style with Name * and field errors', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn((event) => event.preventDefault())
    const onSelectAgent = vi.fn()
    const onTitleChange = vi.fn()
    render(
      <CreateChatModal
        open
        agents={[agent]}
        selectedAgentName=""
        title=""
        pending={false}
        formError="请填写 Chat 名称"
        nameError="请填写名称"
        onClose={() => undefined}
        onSelectAgent={onSelectAgent}
        onTitleChange={onTitleChange}
        onSubmit={onSubmit}
      />,
    )

    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getAllByText('*')).toHaveLength(2)
    expect(screen.getByRole('alert')).toHaveTextContent('请填写 Chat 名称')
    expect(screen.getByText('请填写名称')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Chat 名称（可重名）').closest('label')).toHaveClass('is-error')

    await user.type(screen.getByPlaceholderText('Chat 名称（可重名）'), 'My Chat')
    expect(onTitleChange).toHaveBeenCalled()
    await user.selectOptions(screen.getByLabelText('Agent'), 'assistant')
    expect(onSelectAgent).toHaveBeenCalledWith('assistant')
    // 可选的默认 Environment（可空）：未提供 live 环境时只有 (None) 选项。
    expect(screen.getByLabelText('Environment')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '（无）' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认创建' }))
    expect(onSubmit).toHaveBeenCalled()
  })
})
