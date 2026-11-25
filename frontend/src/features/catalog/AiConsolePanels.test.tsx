import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { AgentsPanel, ModelsPanel, ProvidersPanel } from '@/features/catalog/AiConsolePanels'
import type { ModelConfigDTO, ModelDTO } from '@/shared/api/generated/contracts'

const baseConfig: ModelConfigDTO = {
  limit: { context: 128000, output: 8192 },
  abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, messageLevelEffort: false, inputModalities: ['TEXT'] },
  pricing: {
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      rules: [],
      changes: []
    },
  defaultVariant: 'default',
  variants: [{ id: 'default', promptCacheRetention: 'NONE' }],
}

function modelWithProvider(): ModelDTO {
  return {
    providerName: 'minimax',
    type: 'MODEL',
    name: 'MiniMax',
    modelId: 'minimax',
    description: null,
    config: baseConfig,
    version: '1',
    createdAt: 0,
    updatedAt: 0,
  }
}

describe('AiConsolePanels', () => {
  it('renders agent/model/provider cards and action hooks', async () => {
    const user = userEvent.setup()
    const onCreate = vi.fn()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const model = modelWithProvider()
    render(
      <>
        <AgentsPanel
          agents={[
            {
              name: 'assistant',
              description: 'd',
              systemPrompt: null,
              execution: { driver: 'builtin', settings: { modelName: 'm1', variant: 'default' } },
              config: {
                tools: ['bash'],
                skills: [],
                subagents: [],
              },
              version: '1',
              createdAt: 0,
              updatedAt: 0,
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
              promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
              baseUrl: null,
              credential: { configured: true },
              modelCallTimeoutMillis: '1',
              modelCallIdleTimeoutMillis: '1',
              version: '1',
              createdAt: 0,
              updatedAt: 0,
              modelHttpRetryStatusCodes: null,
            },
          ]}
          deletePending={false}
          onCreate={onCreate}
          onEdit={onEdit}
          onDelete={onDelete}
        />
      </>,
    )
    expect(screen.getByText('assistant')).toBeInTheDocument()
    // Model 卡片使用全局唯一逻辑名。
    expect(screen.getAllByText('MiniMax').length).toBeGreaterThan(0)
    expect(screen.getByText('上下文 128000 · 输出 8192')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '编辑 assistant' }))
    expect(onEdit).toHaveBeenCalled()
  })
})
