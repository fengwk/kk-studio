import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ProviderDraft } from '@/features/catalog/ai-console-types'
import { emptyAgentDraft } from '@/features/catalog/ai-agent-draft-codec'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { AgentForm } from '@/features/catalog/AiAgentResourceForm'
import { ModelForm } from '@/features/catalog/AiModelResourceForm'
import { ProviderForm } from '@/features/catalog/AiProviderResourceForm'
import { PricingTtlInputs } from '@/features/catalog/PricingTtlInputs'
import type { ModelDTO, ProviderDTO } from '@/shared/api/generated/contracts'

/**
 * 字段错误的可访问关联：出错控件 aria-invalid，错误文案由 aria-describedby 从控件可达，
 * 且文案不进入可访问名称（不放在包裹控件的 label 内）。
 */
function describedByText(control: HTMLElement): string[] {
  return (control.getAttribute('aria-describedby') ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .map((id) => document.getElementById(id)?.textContent?.trim() ?? '')
}

const provider: ProviderDTO = {
  name: 'minimax',
  description: null,
  providerType: 'openai',
  promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
  baseUrl: null,
  credential: { configured: true },
  modelCallTimeoutMillis: '1800000',
  modelCallIdleTimeoutMillis: '120000',
  version: '1',
  createdAt: 0,
  updatedAt: 0,
  modelHttpRetryStatusCodes: null,
}

function providerDraft(): ProviderDraft {
  return {
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    credentialConfigured: false,
    clearCredential: false,
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
    modelHttpRetryStatusCodes: null,
  }
}

function modelFixture(): ModelDTO {
  return {
    providerName: 'minimax',
    type: 'MODEL',
    name: 'MiniMax',
    modelId: 'minimax-variants',
    description: null,
    config: {
      limit: { context: 128000, output: 8192 },
      abilities: {
        tools: true,
        reasoning: false,
        midConversationSystemMessages: false,
        messageLevelEffort: false,
        inputModalities: ['TEXT'],
      },
      pricing: {
        base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
        rules: [],
        changes: [],
      },
      defaultVariant: 'default',
      variants: [
        { id: 'default', promptCacheRetention: 'NONE' },
        { id: 'fast', promptCacheRetention: 'NONE' },
      ],
    },
    version: '1',
    createdAt: 0,
    updatedAt: 0,
  }
}

describe('Catalog 表单字段错误的可访问关联', () => {
  it('关联 Provider 名称与基础 URL 的错误文案，且错误文案不进入可访问名称', () => {
    render(
      <ProviderForm
        draft={providerDraft()}
        fieldErrors={{ name: '请填写名称', baseUrl: '请填写基础 URL' }}
        onChange={() => undefined}
      />,
    )

    const nameInput = screen.getByPlaceholderText('minimax')
    expect(nameInput).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(nameInput)).toEqual(['请填写名称'])
    // 可访问名称只来自标签：错误文案若落在包裹控件的 label 内就会被并入名称。
    expect(screen.getByRole('textbox', { name: '名称' })).toBe(nameInput)
    expect(screen.getByText('请填写名称').closest('label')).toBeNull()

    const baseUrlInput = screen.getByPlaceholderText('https://api.example.invalid/v1')
    expect(baseUrlInput).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(baseUrlInput)).toEqual(['请填写基础 URL'])
    expect(screen.getByRole('textbox', { name: '基础 URL' })).toBe(baseUrlInput)
    expect(screen.getByText('请填写基础 URL').closest('label')).toBeNull()
  })

  it('关联 Model 表单的文本框、选择、数字与输入类型错误', () => {
    render(
      <ModelForm
        draft={emptyModelDraft()}
        mode="create"
        providers={[provider]}
        fieldErrors={{
          providerName: '请选择提供商',
          name: '请填写 Model 名称',
          modelId: '请填写模型 ID',
          contextWindow: '上下文窗口无效',
          inputLimit: '输入上限无效',
          defaultVariant: '请选择默认预设',
          inputModalities: '请至少选择一种输入类型',
        }}
        onChange={() => undefined}
      />,
    )

    const nameInput = screen.getByRole('textbox', { name: '名称' })
    expect(nameInput).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(nameInput)).toEqual(['请填写 Model 名称'])
    expect(screen.getByText('请填写 Model 名称').closest('label')).toBeNull()

    // 新建态带 list 属性，输入框的可访问角色是 combobox，因此按可访问名定位。
    const modelIdInput = screen.getByRole('combobox', { name: '模型 ID' })
    expect(modelIdInput).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(modelIdInput)).toEqual(['请填写模型 ID'])

    const providerSelect = screen.getByLabelText('提供商')
    expect(providerSelect).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(providerSelect)).toEqual(['请选择提供商'])

    const contextWindow = screen.getByLabelText('上下文窗口')
    expect(contextWindow).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(contextWindow)).toEqual(['上下文窗口无效'])

    // 已有关联说明的字段要把说明与错误一起交给 aria-describedby。
    const inputLimit = screen.getByLabelText('输入上限')
    expect(inputLimit).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(inputLimit)).toEqual(['输入上限无效'])

    const defaultVariant = screen.getByLabelText('默认预设')
    expect(defaultVariant).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(defaultVariant)).toEqual(['请选择默认预设'])

    const inputModalities = screen.getByRole('group', { name: /输入类型/ })
    expect(describedByText(inputModalities)).toEqual(['请至少选择一种输入类型'])
  })

  it('关联 Agent 表单的名称、模型、预设覆盖与能力目录错误', () => {
    render(
      <AgentForm
        draft={{ ...emptyAgentDraft(), model: 'MiniMax' }}
        models={[modelFixture()]}
        fieldErrors={{
          name: '请填写名称',
          model: '请选择默认模型',
          variant: '请选择预设覆盖',
          tools: '请选择工具',
        }}
        onChange={() => undefined}
      />,
    )

    const nameInput = screen.getByRole('textbox', { name: '名称' })
    expect(nameInput).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(nameInput)).toEqual(['请填写名称'])

    const modelSelect = screen.getByLabelText('默认模型')
    expect(modelSelect).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(modelSelect)).toEqual(['请选择默认模型'])

    const variantSelect = screen.getByLabelText('模型预设覆盖')
    expect(describedByText(variantSelect)).toEqual(['请选择预设覆盖'])

    const toolsPanel = screen.getByRole('group', { name: /^工具/ })
    expect(describedByText(toolsPanel)).toEqual(['请选择工具'])
  })

  it('关联 TTL 缓存写入价每一行的错误', () => {
    render(
      <PricingTtlInputs
        rows={[
          { draftKey: 'ttl-1', seconds: '', rate: '' },
          { draftKey: 'ttl-2', seconds: '3600', rate: '' },
        ]}
        onChange={() => undefined}
      />,
    )

    const [invalidSeconds, validSeconds] = screen.getAllByLabelText('TTL 秒数')
    expect(invalidSeconds).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(invalidSeconds)).toEqual(['请输入正整数秒数。'])
    expect(screen.getByText('请输入正整数秒数。').closest('label')).toBeNull()
    expect(validSeconds).not.toHaveAttribute('aria-invalid')

    const [, unpricedRate] = screen.getAllByLabelText('缓存写入')
    expect(unpricedRate).toHaveAttribute('aria-invalid', 'true')
    expect(describedByText(unpricedRate)).toEqual(['请输入费率；零价请填 0。'])
  })
})
