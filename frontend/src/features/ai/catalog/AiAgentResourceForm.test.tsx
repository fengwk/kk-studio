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
    const longDescription =
      'Execute shell commands in the configured environment and return the captured output without losing long diagnostic context.'
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
            { name: 'bash', version: '1', description: longDescription, type: 'PLATFORM' },
            { name: 'lsp', version: '1', description: 'lsp', type: 'ENVIRONMENT' },
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
    const bashInput = screen.getByLabelText(/bash/)
    const bashOption = bashInput.closest('.capability-option')
    expect(bashOption).toHaveClass('capability-option-detailed')
    expect(bashOption?.querySelector('.capability-option-body')).toBeInTheDocument()
    expect(bashOption?.querySelector('.capability-option-heading .capability-name')).toHaveTextContent('bash')
    expect(bashOption?.querySelector('.capability-option-meta')).toHaveTextContent('1')
    expect(bashOption?.querySelector('.capability-option-description')).toHaveTextContent(longDescription)
    expect(bashOption?.querySelector('.capability-option-description')).toHaveAttribute('title', longDescription)
    await user.click(missingTool)
    expect(missingTool).not.toBeChecked()
    await user.click(bashInput)
    expect(bashInput).toBeChecked()
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

  it.each(['deleted/original-model', 'off-page/original-model'])(
    'shows a missing edit Model %s as an unavailable orphan without unrelated variants',
    (modelName) => {
      const draft: AgentDraft = {
        ...emptyAgentDraft(),
        name: 'assistant',
        model: modelName,
        variant: 'persisted-override',
      }
      const unrelatedModel = {
        ...modelWithVariants(),
        providerName: 'loaded',
        name: 'unrelated-model',
      }

      render(
        <AgentForm
          draft={draft}
          mode="edit"
          models={[unrelatedModel]}
          onChange={() => undefined}
        />,
      )

      const modelSelect = screen.getByLabelText('Default Model')
      expect(modelSelect).toHaveValue(modelName)
      expect(modelSelect).toBeDisabled()
      expect(modelSelect).toHaveAttribute('aria-describedby', 'agent-model-identity-status')
      expect(screen.getByRole('option', { name: `${modelName} (不可用)` })).toBeDisabled()

      const variantSelect = screen.getByLabelText('Default Variant Override')
      expect(variantSelect).toBeDisabled()
      expect(variantSelect).toHaveAttribute('aria-describedby', 'agent-model-variant-status')
      expect(screen.getByRole('option', { name: 'persisted-override (不可用)' })).toBeDisabled()
      expect(screen.queryByRole('option', { name: 'fast' })).not.toBeInTheDocument()
      expect(screen.getByText('不可用；保存其他字段时仍保留原始身份。')).toBeInTheDocument()
      expect(
        screen.getByText('引用的 Model 未加载，Variant 选项不可用；已保存的覆盖值会保留。'),
      ).toBeInTheDocument()
      expect(screen.getByPlaceholderText('用途说明')).not.toBeDisabled()
    },
  )
})
