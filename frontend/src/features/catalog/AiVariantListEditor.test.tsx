import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { VariantListEditor } from '@/features/catalog/AiVariantListEditor'
import { newVariantDraft } from '@/features/catalog/ai-resource-draft-primitives'
import type { PromptCacheRetention } from '@/shared/api/generated/contracts'
import { setLocale } from '@/shared/i18n'

function Editor({ supported }: { supported: PromptCacheRetention[] }) {
  const [variants, setVariants] = useState([
    newVariantDraft({ id: 'first', promptCacheRetention: supported.includes('SHORT') ? 'SHORT' : 'NONE' }),
  ])
  return (
    <VariantListEditor
      label="Variants"
      variants={variants}
      defaultVariant="first"
      reasoning
      promptCacheRetentions={supported}
      onChange={setVariants}
    />
  )
}

describe('VariantListEditor cache retention', () => {
  it.each([
    { supported: ['NONE', 'SHORT', 'LONG'] as PromptCacheRetention[], expected: ['不发送缓存指令', 'SHORT', 'LONG'], added: 'SHORT' },
    { supported: ['NONE'] as PromptCacheRetention[], expected: ['不发送缓存指令'], added: 'NONE' },
  ])('limits options and prefills new variants for $supported', async ({ supported, expected, added }) => {
    // 下拉选项与新建默认值都取自当前 Provider 的能力。
    setLocale('zh-CN')
    const user = userEvent.setup()
    render(<Editor supported={supported} />)
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(expected)
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    expect(screen.getByLabelText('提示缓存留存 2')).toHaveAttribute('data-value', added)
  })

  it('preserves the existing selection when provider capabilities change', async () => {
    // 切换到不支持 SHORT 的 Provider 时不得静默改写已输入的档位。
    setLocale('zh-CN')
    const user = userEvent.setup()
    const { rerender } = render(<Editor supported={['NONE', 'SHORT', 'LONG']} />)
    rerender(<Editor supported={['NONE']} />)
    expect(screen.getByLabelText('提示缓存留存 1')).toHaveAttribute('data-value', 'SHORT')
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      'SHORT · 不可用',
      '不发送缓存指令',
    ])
    await user.click(screen.getByRole('button', { name: '提示缓存留存 1' }))
    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    expect(screen.getByLabelText('提示缓存留存 2')).toHaveAttribute('data-value', 'NONE')
  })
})
