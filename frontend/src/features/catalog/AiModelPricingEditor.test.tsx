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

  it('labels the weekday checkboxes with ISO weekday names', () => {
    // 测试意图：weekday 1..7 按 ISO 星期解释（1=周一，与后端 DayOfWeek 一致），每个复选框都显示译名而不是原始 key。
    setLocale('en-US')
    render(<PricingHarness initial={pricing()} />)
    openFirstRule()

    for (const day of ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']) {
      expect(screen.getByRole('checkbox', { name: day })).toBeInTheDocument()
    }
    fireEvent.click(screen.getByRole('checkbox', { name: 'Mon' }))
    expect(JSON.parse(screen.getByTestId('pricing-json').textContent!).overrides[0].when.time.weekdays)
      .toEqual([1])
  })

  it('labels every condition in the add menu with its translated name', () => {
    // 测试意图：添加菜单的选项必须与条件块标题共用已定义的 i18n key，否则会显示原始 key。
    setLocale('en-US')
    const initial = pricing()
    initial.overrides[0]!.when = {}
    render(<PricingHarness initial={initial} />)
    openFirstRule()

    fireEvent.click(screen.getByRole('button', { name: 'Add condition' }))
    const labels = screen.getAllByRole('option').map((option) => option.textContent)
    expect(labels).toEqual(['Context tokens', 'Service tier', 'Date', 'Time', 'All of', 'Any of', 'Not'])
  })
})
