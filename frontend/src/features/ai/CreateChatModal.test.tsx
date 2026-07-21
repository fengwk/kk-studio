import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CreateChatModal } from '@/features/ai/CreateChatModal'

describe('CreateChatModal', () => {
  it('renders nothing when closed', () => {
    const { container } = render(
      <CreateChatModal
        open={false}
        agents={[]}
        selectedAgentId=""
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

  it('allows optional title and optional default agent', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn((event) => event.preventDefault())
    const onSelectAgent = vi.fn()
    const onTitleChange = vi.fn()
    render(
      <CreateChatModal
        open
        agents={[
          {
            id: 'a1',
            name: 'assistant',
            description: null,
            systemPrompt: null,
            modelId: 'm1',
            variant: 'default',
            config: {
              environmentName: null,
              tools: [],
              skills: [],
              allowedSubagents: [],
              executionPolicy: {},
            },
            createTime: null,
            updateTime: null,
          },
        ]}
        selectedAgentId=""
        title=""
        pending={false}
        onClose={() => undefined}
        onSelectAgent={onSelectAgent}
        onTitleChange={onTitleChange}
        onSubmit={onSubmit}
      />,
    )
    await user.type(screen.getByPlaceholderText('Chat 标题'), 'My Chat')
    expect(onTitleChange).toHaveBeenCalled()
    await user.selectOptions(screen.getByLabelText('Default Agent（可选）'), 'a1')
    expect(onSelectAgent).toHaveBeenCalledWith('a1')
    await user.click(screen.getByRole('button', { name: '确认创建' }))
    expect(onSubmit).toHaveBeenCalled()
  })
})
