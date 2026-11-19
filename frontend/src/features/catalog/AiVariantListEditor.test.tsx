import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { VariantListEditor } from '@/features/catalog/AiVariantListEditor'
import { newVariantDraft } from '@/features/catalog/ai-resource-draft-primitives'
import type { ModelCapabilitiesDTO, PromptCacheRetention } from '@/shared/api/generated/contracts'
import { setLocale } from '@/shared/i18n'

function capabilities(
  retentions: PromptCacheRetention[],
  defaultPromptCacheRetention?: PromptCacheRetention,
): ModelCapabilitiesDTO {
  return {
    promptCacheOptions: retentions.map((retention) => ({
      retention,
      ...(retention === 'SHORT' ? { ttlSeconds: '3600', writeMultiplier: 1.5 } : {}),
    })),
    defaultPromptCacheRetention,
    automaticCache: false,
  }
}

function Editor({
  modelCapabilities,
  initialRetention = 'SHORT',
}: {
  modelCapabilities: ModelCapabilitiesDTO | null
  initialRetention?: PromptCacheRetention | ''
}) {
  const [variants, setVariants] = useState([
    newVariantDraft({ id: 'first', promptCacheRetention: initialRetention }),
  ])
  return (
    <VariantListEditor
      label="Variants"
      variants={variants}
      defaultVariant="first"
      reasoning
      capabilities={modelCapabilities}
      onChange={setVariants}
    />
  )
}

describe('VariantListEditor model capabilities', () => {
  it('renders backend retention options with TTL and read-only automatic-cache state', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()
    render(<Editor modelCapabilities={capabilities(['NONE', 'SHORT', 'LONG'], 'SHORT')} />)
    expect(screen.getByRole('status')).toHaveTextContent('自动缓存: 非自动缓存')
    expect(screen.getByRole('status')).toHaveTextContent('服务商默认值: 标准')
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      '关闭', '标准 · 3600 秒 · 写入 ×1.5', '长',
    ])
  })

  it('preserves a saved option absent from new model capabilities and applies only the backend default to new variants', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()
    const { rerender } = render(<Editor modelCapabilities={capabilities(['NONE', 'SHORT'], 'SHORT')} />)
    rerender(<Editor modelCapabilities={capabilities(['NONE'], 'NONE')} />)
    expect(screen.getByLabelText('提示缓存留存 1')).toHaveAttribute('data-value', 'SHORT')
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      '标准 · 不可用', '关闭',
    ])
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    expect(screen.getByLabelText('提示缓存留存 2')).toHaveAttribute('data-value', 'NONE')
  })

  it('shows automatic-cache status without a retention selector and defaults new variants to NONE', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()
    render(<Editor modelCapabilities={{
      promptCacheOptions: [],
      defaultPromptCacheRetention: 'NONE',
      automaticCache: true,
    }} initialRetention="NONE" />)

    expect(screen.getByRole('status')).toHaveTextContent('自动缓存: 由服务商自动处理')
    expect(screen.queryByLabelText('提示缓存留存 1')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    expect(screen.queryByLabelText('提示缓存留存 2')).not.toBeInTheDocument()
  })

  it('does not guess a selection while capabilities are unknown', () => {
    setLocale('zh-CN')
    render(<Editor modelCapabilities={null} initialRetention="" />)
    expect(screen.getByLabelText('提示缓存留存 1')).toHaveAttribute('data-value', '')
    expect(screen.getByLabelText('提示缓存留存 1')).toBeDisabled()
  })
})
