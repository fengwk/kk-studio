import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { PricingRuleEditor } from '@/features/ai/catalog/AiPricingRuleEditor'
import { newPricingRuleDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import { setLocale } from '@/shared/i18n'

describe('PricingRuleEditor', () => {
  // 添加、编辑与删除规则都以新数组回传，时段可关闭后只保留上下文门槛。
  it('adds, edits and removes conditional prices', () => {
    setLocale('en-US')
    const onChange = vi.fn()
    const rule = newPricingRuleDraft({ timeZone: 'UTC', start: '01:00', end: '02:00' })
    const { rerender } = render(<PricingRuleEditor rules={[]} onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: 'Add rule' }))
    expect(onChange.mock.calls[0][0]).toHaveLength(1)

    rerender(<PricingRuleEditor rules={[rule]} onChange={onChange} />)
    fireEvent.change(screen.getByLabelText('Minimum context tokens 1'), {
      target: { value: '200k000' },
    })
    expect(onChange.mock.lastCall?.[0][0].minContextTokens).toBe('200000')

    fireEvent.click(screen.getByRole('checkbox'))
    expect(onChange.mock.lastCall?.[0][0].timeWindowEnabled).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: 'Delete rule' }))
    expect(onChange.mock.lastCall?.[0]).toEqual([])
  })

  it('hides the time fields when the rule has no time window', () => {
    setLocale('en-US')
    render(
      <PricingRuleEditor
        rules={[newPricingRuleDraft({ timeWindowEnabled: false, minContextTokens: '1000' })]}
        onChange={vi.fn()}
      />,
    )
    expect(screen.queryByLabelText('Time zone 1')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Minimum context tokens 1')).toHaveValue('1000')
  })
})
