import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ModelPricingEditor } from '@/features/catalog/AiModelPricingEditor'
import type { ModelPricingDraft } from '@/features/catalog/ai-console-types'
import { setLocale } from '@/shared/i18n'

function pricing(): ModelPricingDraft {
  return {
    base: {
      input: '1',
      output: '2',
      cacheRead: '0.1',
      cacheWrite: '0.2',
      cacheWriteByTtl: {},
      reasoning: null,
    },
    overrides: [{
      id: 'ov-time',
      when: { time: { start: '00:00', end: '24:00', timezone: 'UTC' } },
      rates: {},
    }],
    multipliers: [],
  }
}

function PricingHarness({ initial }: { initial: ModelPricingDraft }) {
  const [value, setValue] = useState(initial)
  return (
    <>
      <output data-testid="pricing-json">{JSON.stringify(value)}</output>
      <ModelPricingEditor pricing={value} onChange={setValue} />
    </>
  )
}

function openFirstRule() {
  fireEvent.click(screen.getByRole('button', { name: /ov-time/ }))
}

describe('ModelPricingEditor source controls', () => {
  it('shows and edits end 24:00 without changing the draft while merely previewing', () => {
    setLocale('en-US')
    const onChange = vi.fn()
    render(<ModelPricingEditor pricing={pricing()} onChange={onChange} />)
    openFirstRule()

    const start = screen.getByLabelText('Start time')
    const end = screen.getByLabelText('End time')
    expect(start).toHaveValue('00:00')
    expect(end).toHaveValue('24:00')
    expect(end).toHaveAttribute('type', 'text')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('keeps 24:00 visible while editing the start time', () => {
    setLocale('en-US')
    render(<PricingHarness initial={pricing()} />)
    openFirstRule()

    fireEvent.change(screen.getByLabelText('Start time'), { target: { value: '06:30' } })
    expect(screen.getByLabelText('Start time')).toHaveValue('06:30')
    expect(screen.getByLabelText('End time')).toHaveValue('24:00')
    expect(screen.getByTestId('pricing-json').textContent).toContain(
      '"start":"06:30","end":"24:00"',
    )
  })

  it('separates TTL inherit from override and keeps an explicit empty map after removing the last row', () => {
    setLocale('en-US')
    const initial = pricing()
    initial.overrides[0]!.when = {}
    initial.overrides[0]!.rates = { input: '6' }
    render(<PricingHarness initial={initial} />)
    openFirstRule()

    let toggle = screen.getByRole('checkbox', { name: 'Override cache write TTL' })
    expect(toggle).not.toBeChecked()
    expect(screen.queryByLabelText('TTL seconds')).not.toBeInTheDocument()

    fireEvent.click(toggle)
    toggle = screen.getByRole('checkbox', { name: 'Override cache write TTL' })
    expect(toggle).toBeChecked()
    expect(JSON.parse(screen.getByTestId('pricing-json').textContent!))
      .toMatchObject({ overrides: [{ rates: { input: '6', cacheWriteByTtl: {} } }] })

    fireEvent.click(screen.getByRole('button', { name: 'Add TTL override' }))
    expect(screen.getByLabelText('TTL seconds')).toHaveValue('300')
    fireEvent.click(screen.getByRole('button', { name: 'Remove TTL override' }))
    expect(screen.queryByLabelText('TTL seconds')).not.toBeInTheDocument()
    expect(JSON.parse(screen.getByTestId('pricing-json').textContent!))
      .toMatchObject({ overrides: [{ rates: { cacheWriteByTtl: {} } }] })

    fireEvent.click(toggle)
    expect(JSON.parse(screen.getByTestId('pricing-json').textContent!).overrides[0].rates)
      .not.toHaveProperty('cacheWriteByTtl')
  })
})
