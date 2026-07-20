import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AgentForm } from '@/features/ai/AiAgentResourceForm'
import type { AgentDraft } from '@/features/ai/ai-console-types'
import { emptyAgentDraft } from '@/features/ai/ai-agent-draft-codec'
import type {
  AgentModelConfigDTO,
  AgentModelView,
} from '@/shared/api/contracts'

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
  variants: [{ id: 'default' }, { id: 'fast' }],
}

function modelWithVariants(): AgentModelView {
  return {
    id: 'm1',
    providerId: 'p1',
    providerName: 'minimax',
    name: 'MiniMax',
    description: null,
    config: baseConfig,
    version: 1,
    createTime: null,
    updateTime: null,
  }
}

describe('AgentForm current contracts', () => {
  it('selects model/variant/environment and marks invalid tools', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        tools: ['missing-tool'],
        environmentName: 'gone',
      })
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          agents={[
            {
              id: 'a2',
              name: 'researcher',
              description: null,
              systemPrompt: null,
              modelId: 'm1',
              variant: 'default',
              config: null,
              version: 1,
              createTime: null,
              updateTime: null,
            },
          ]}
          environments={[
            {
              name: 'platform',
              status: 'READY',
              lastSeen: null,
              tools: [{ name: 'bash', version: '1', description: 'shell' }],
              skills: [{ name: 'dev', description: 'dev' }],
            },
            {
              name: 'local',
              status: 'READY',
              lastSeen: null,
              tools: [{ name: 'lsp', version: '1', description: 'lsp' }],
              skills: [],
            },
          ]}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)
    // 已配置但不在候选中的 tool 仍展示且可取消勾选，不会被自动清掉；无红色阻断提示。
    expect(screen.queryByText(/不在 live registry/)).not.toBeInTheDocument()
    expect(screen.queryByText(/无效\/离线 Tools/)).not.toBeInTheDocument()
    const missingTool = screen.getByLabelText(/missing-tool/)
    expect(missingTool).toBeChecked()
    await user.click(missingTool)
    expect(missingTool).not.toBeChecked()
    await user.selectOptions(screen.getByLabelText('Environment'), 'local')
    await user.click(screen.getByLabelText(/bash/))
    await user.click(screen.getByLabelText(/researcher/))
    expect(screen.getByLabelText(/bash/)).toBeChecked()
    expect(screen.getByLabelText(/researcher/)).toBeChecked()
  })
})