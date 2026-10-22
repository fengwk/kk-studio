import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AgentsPanel, ModelsPanel, ProvidersPanel } from '@/features/catalog/AiConsolePanels'
import type { AgentModelView } from '@/features/catalog/AgentModelView'
import { ChatCardsPanel } from '@/features/chat/ChatCardsPanel'
import type { AgentModelConfigDTO, ChatDTO } from '@/shared/api/generated/contracts'

const baseConfig: AgentModelConfigDTO = {
  limit: { context: 128000, output: 8192 },
  abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
  pricing: {
    currency: 'USD',
    pricingTier: 'default',
    serviceTier: 'default',
    serviceTierMultiplier: 1,
    version: 'v1',
    inputPerMillionTokens: 0,
    outputPerMillionTokens: 0,
    cacheReadPerMillionTokens: 0,
    cacheWritePerMillionTokens: 0,
    cacheWriteLongPerMillionTokens: 0,
    reasoningPerMillionTokens: 0,
  },
  defaultVariant: 'default',
  variants: [{ id: 'default' }],
}

function modelWithProvider(): AgentModelView {
  return {
    providerName: 'minimax',
    name: 'MiniMax',
    modelId: 'minimax',
    description: null,
    config: baseConfig,
    version: '1',
    createTime: 0,
    updateTime: 0,
  }
}

describe('AiConsolePanels', () => {
  it('renders chat/agent/model/provider cards and action hooks', async () => {
    const user = userEvent.setup()
    const onCreate = vi.fn()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const model = modelWithProvider()
    render(
      <MemoryRouter>
        <ChatCardsPanel
          chats={[
            {
              id: 'c1',
              title: 'One',
              agentName: 'missing',
              environmentName: null,
              yoloEnabled: false,
              version: '1',
              createTime: 0,
              updateTime: 0,
            } satisfies ChatDTO,
          ]}
          agents={[]}
          onCreate={onCreate}
        />
        <AgentsPanel
          agents={[
            {
              name: 'assistant',
              description: 'd',
              systemPrompt: null,
              type: 'USER',
              model: 'm1',
              variant: 'default',
              config: {
                inheritParentEnvironment: true,
                tools: ['bash'],
                skills: [],
                subagents: [],
              },
              version: '1',
              createTime: 0,
              updateTime: 0,
            },
          ]}
          models={[model]}
          deletePending={false}
          onCreate={onCreate}
          onEdit={onEdit}
          onDelete={onDelete}
        />
        <ModelsPanel
          models={[model]}
          deletePending={false}
          onCreate={onCreate}
          onEdit={onEdit}
          onDelete={onDelete}
        />
        <ProvidersPanel
          providers={[
            {
              name: 'minimax',
              description: null,
              providerType: 'openai',
              baseUrl: null,
              configured: true,
              modelCallTimeoutMillis: '1',
              modelCallIdleTimeoutMillis: '1',
              version: '1',
              createTime: 0,
              updateTime: 0,
              modelHttpRetryStatusCodes: null,
            },
          ]}
          deletePending={false}
          onCreate={onCreate}
          onEdit={onEdit}
          onDelete={onDelete}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText('One')).toBeInTheDocument()
    expect(screen.getByText('assistant')).toBeInTheDocument()
    // Model 卡片使用规范的 provider/model 展示 ref。
    expect(screen.getAllByText(/minimax\/MiniMax/i).length).toBeGreaterThan(0)
    expect(screen.getByText(/ctx 128000/)).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: '新建 Chat' })[0]!)
    expect(onCreate).toHaveBeenCalled()
  })
})