import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AgentForm } from '@/features/ai/catalog/AiAgentResourceForm'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-agent-draft-codec'
import type {
  AgentModelConfigDTO,
  AgentModelView,
} from '@/shared/api/contracts/ai-catalog'

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
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

describe('AgentForm current contracts', () => {
  it('selects model/variant and toggles unified tools and live skills', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        tools: ['missing-tool'],
      })
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[
            {
              name: 'local',
              status: 'READY',
              lastSeen: null,
              tools: [],
              skills: [{ name: 'dev', description: 'dev' }],
            },
          ]}
          toolCatalog={[
            { name: 'bash', version: '1', description: 'shell' },
            { name: 'lsp', version: '1', description: 'lsp' },
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
    await user.click(screen.getByLabelText(/bash/))
    expect(screen.getByLabelText(/bash/)).toBeChecked()
    await user.click(screen.getByLabelText(/dev/))
    expect(screen.getByLabelText(/dev/)).toBeChecked()
  })

  it('renders field-level errors for tools and skills without an Agent Environment field', () => {
    render(
      <AgentForm
        draft={emptyAgentDraft(modelWithVariants())}
        models={[modelWithVariants()]}
        fieldErrors={{
          variant: '请选择 Variant',
          tools: 'Tools 冲突',
          skills: 'Skills 冲突',
        }}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByText('请选择 Variant')).toBeInTheDocument()
    expect(screen.getByText('Tools 冲突')).toBeInTheDocument()
    expect(screen.getByText('Skills 冲突')).toBeInTheDocument()
    expect(screen.queryByLabelText('Environment')).not.toBeInTheDocument()
  })
})
