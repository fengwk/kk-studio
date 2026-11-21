import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { VariantListEditor } from '@/features/catalog/AiVariantListEditor'
import { newVariantDraft } from '@/features/catalog/ai-resource-draft-primitives'
import type { ModelCapabilitiesDTO, PromptCacheRetention } from '@/shared/api/generated/contracts'
import { setLocale } from '@/shared/i18n'

// 四种后端缓存状态的说明文案，以及尚未选定 Provider/模型时的提示。
const PROVIDER_MANAGED_TEXT = 'Automatic (managed by provider)'
const UNMANAGED_TEXT = 'Determined by endpoint · Studio does not configure it'
const UNSUPPORTED_TEXT = 'Prompt caching is not supported'
const UNKNOWN_TEXT = 'Select a provider and enter a model ID to check prompt caching'
const API_ERROR = 'Capabilities lookup failed: 502 Bad Gateway'

function capabilities(
  promptCacheState: ModelCapabilitiesDTO['promptCacheState'],
  retentions: PromptCacheRetention[] = [],
  defaultPromptCacheRetention?: PromptCacheRetention,
): ModelCapabilitiesDTO {
  return {
    promptCacheState,
    promptCacheOptions: retentions.map((retention) => ({
      retention,
      ...(retention === 'SHORT' ? { ttlSeconds: '3600', writeMultiplier: 1.5 } : {}),
    })),
    defaultPromptCacheRetention,
  }
}

function Editor({
  modelCapabilities,
  initialRetentions = ['SHORT'],
  capabilitiesLoading = false,
  capabilitiesError,
}: {
  modelCapabilities: ModelCapabilitiesDTO | null
  initialRetentions?: (PromptCacheRetention | '')[]
  capabilitiesLoading?: boolean
  capabilitiesError?: string
}) {
  const [variants, setVariants] = useState(() =>
    initialRetentions.map((retention, index) =>
      newVariantDraft({ id: `variant-${index + 1}`, promptCacheRetention: retention }),
    ),
  )
  return (
    <VariantListEditor
      label="Variants"
      variants={variants}
      defaultVariant={variants[0]?.id ?? ''}
      reasoning
      capabilities={modelCapabilities}
      capabilitiesLoading={capabilitiesLoading}
      capabilitiesError={capabilitiesError}
      onChange={setVariants}
    />
  )
}

describe('VariantListEditor model cache state', () => {
  it('renders backend retention options for configurable caching without any summary line', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()
    render(<Editor modelCapabilities={capabilities('CONFIGURABLE', ['NONE', 'SHORT', 'LONG'], 'SHORT')} />)

    // 摘要行不再存在：状态只由字段自身表达。
    expect(screen.queryByText(/自动缓存/)).not.toBeInTheDocument()
    expect(screen.queryByText(/服务商默认值/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      '关闭', '标准 · 3600 秒 · 写入 ×1.5', '长',
    ])
  })

  it('preserves a saved option absent from new model capabilities and applies only the backend default to new variants', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()
    const { rerender } = render(
      <Editor modelCapabilities={capabilities('CONFIGURABLE', ['NONE', 'SHORT'], 'SHORT')} />,
    )
    rerender(<Editor modelCapabilities={capabilities('CONFIGURABLE', ['NONE'], 'NONE')} />)
    expect(screen.getByLabelText('提示缓存留存 1')).toHaveAttribute('data-value', 'SHORT')
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      '标准 · 不可用', '关闭',
    ])
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    await user.click(screen.getByRole('button', { name: '添加预设' }))
    expect(screen.getByLabelText('提示缓存留存 2')).toHaveAttribute('data-value', 'NONE')
  })

  it('labels every variant and explains provider-managed caching instead of offering a select', async () => {
    setLocale('en-US')
    const user = userEvent.setup()
    render(
      <Editor
        modelCapabilities={capabilities('PROVIDER_MANAGED', [], 'NONE')}
        initialRetentions={['NONE', 'SHORT']}
      />,
    )

    expect(screen.getAllByText('Prompt Cache Retention')).toHaveLength(2)
    expect(screen.getAllByText(PROVIDER_MANAGED_TEXT)).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Prompt Cache Retention 1' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Prompt Cache Retention 2' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Add Variant' }))
    expect(screen.queryByRole('button', { name: 'Prompt Cache Retention 3' })).not.toBeInTheDocument()
  })

  it('explains endpoint-determined caching without offering a select', () => {
    setLocale('en-US')
    render(<Editor modelCapabilities={capabilities('UNMANAGED')} />)

    expect(screen.getByText(UNMANAGED_TEXT)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Prompt Cache Retention 1' })).not.toBeInTheDocument()
  })

  it('explains unsupported caching without offering a select', () => {
    setLocale('en-US')
    render(<Editor modelCapabilities={capabilities('UNSUPPORTED')} />)

    expect(screen.getByText(UNSUPPORTED_TEXT)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Prompt Cache Retention 1' })).not.toBeInTheDocument()
  })

  it('uses the Chinese provider-managed wording', () => {
    setLocale('zh-CN')
    render(
      <Editor modelCapabilities={capabilities('PROVIDER_MANAGED', [], 'NONE')} initialRetentions={['NONE']} />,
    )

    expect(screen.getByText('提示缓存')).toBeInTheDocument()
    expect(screen.getByText('自动（由服务商管理）')).toBeInTheDocument()
    expect(screen.queryByLabelText('提示缓存留存 1')).not.toBeInTheDocument()
  })

  it('asks for a provider and model ID while no capabilities are known', () => {
    setLocale('en-US')
    render(<Editor modelCapabilities={null} initialRetentions={['']} />)

    expect(screen.getByText(UNKNOWN_TEXT)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Prompt Cache Retention 1' })).not.toBeInTheDocument()
  })

  it('reports loading without guessing a state', () => {
    setLocale('en-US')
    render(<Editor modelCapabilities={null} capabilitiesLoading initialRetentions={['']} />)

    expect(screen.getByText('Loading model options…')).toBeInTheDocument()
    expect(screen.queryByText(UNKNOWN_TEXT)).not.toBeInTheDocument()
  })

  it('reports the real API error without guessing a state', () => {
    setLocale('en-US')
    render(<Editor modelCapabilities={null} capabilitiesError={API_ERROR} />)

    expect(screen.getByText(/Could not load prompt cache options/)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(API_ERROR)
    expect(screen.queryByRole('button', { name: 'Prompt Cache Retention 1' })).not.toBeInTheDocument()
  })
})
