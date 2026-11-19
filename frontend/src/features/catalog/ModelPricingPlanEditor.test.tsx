import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { ModelPricingPlanEditor } from '@/features/catalog/ModelPricingPlanEditor'
import { emptyModelPricingDraft, type ModelPricingDraft } from '@/features/catalog/model-pricing-draft'
import { setLocale } from '@/shared/i18n'

function Harness({ initial = emptyModelPricingDraft() }: { initial?: ModelPricingDraft }) {
  const [value, setValue] = useState(initial)
  return <><output data-testid="draft">{JSON.stringify(value)}</output><ModelPricingPlanEditor value={value} onChange={setValue} /></>
}

describe('ModelPricingPlanEditor', () => {
  it('presents blank prices as unpriced and only exposes the fixed three rule groups', () => {
    setLocale('en-US')
    render(<Harness />)
    expect(screen.getByLabelText('Input USD per million tokens')).toHaveValue('')
    expect(screen.getByText('Prices are USD per million tokens. Blank prices are unknown, not zero.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Input length prices' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Service tiers' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Time periods' })).toBeInTheDocument()
    expect(screen.queryByText(/override|multiplier|condition/i)).not.toBeInTheDocument()
  })

  it('keeps reasoning pricing in unknown, follow-output and independent-price states', () => {
    setLocale('en-US')
    render(<Harness />)
    const mode = screen.getByRole('button', { name: 'Independent reasoning pricing' })
    expect(mode).toHaveTextContent('Unknown')
    fireEvent.click(mode)
    fireEvent.click(screen.getByRole('option', { name: 'Follow output price' }))
    expect(JSON.parse(screen.getByTestId('draft').textContent!).definition.base.reasoning).toBeNull()
    fireEvent.click(mode)
    fireEvent.click(screen.getByRole('option', { name: 'Independent price' }))
    expect(screen.getByLabelText('Reasoning')).toHaveValue('')
    fireEvent.change(screen.getByLabelText('Reasoning'), { target: { value: '0' } })
    expect(JSON.parse(screen.getByTestId('draft').textContent!).definition.base.reasoning).toBe('0')
  })

  it('splits a cross-midnight interval at the next-day boundary when editing ends', () => {
    setLocale('en-US')
    const initial = emptyModelPricingDraft()
    initial.definition.timePeriods = [{
      draftKey: 'night', weekdays: [7], start: '22:00', end: '24:00', timezone: 'UTC',
      factors: { factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '' },
    }]
    render(<Harness initial={initial} />)
    const end = screen.getByLabelText('End time')
    fireEvent.change(end, { target: { value: '01:00' } })
    fireEvent.blur(end)
    expect(screen.getAllByLabelText('Start time')).toHaveLength(2)
    expect(screen.getAllByLabelText('End time').map((input) => (input as HTMLInputElement).value))
      .toEqual(['24:00', '01:00'])
    expect(JSON.parse(screen.getByTestId('draft').textContent!).definition.timePeriods.map((period: { weekdays: number[] }) => period.weekdays))
      .toEqual([[7], [1]])
  })

  it('copies a price definition to a UTC change and allows editing its independent rates', () => {
    setLocale('en-US')
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Add price change' }))
    fireEvent.change(screen.getByLabelText('Effective from (UTC)'), { target: { value: '2027-01-01' } })
    const changedInput = screen.getByLabelText('2027-01-01 Input')
    fireEvent.change(changedInput, { target: { value: '0.25' } })
    const draft = JSON.parse(screen.getByTestId('draft').textContent!)
    expect(draft.changes[0]).toMatchObject({ from: '2027-01-01', definition: { base: { input: '0.25' } } })
    expect(draft.changes[0].draftKey).toBeTruthy()
  })

  it('shows duplicate service tier conflicts immediately and removes only the chosen draft row', () => {
    setLocale('en-US')
    render(<Harness />)
    const add = screen.getByRole('button', { name: 'Add service tier' })
    fireEvent.click(add)
    fireEvent.click(add)
    const names = screen.getAllByRole('textbox', { name: 'Service tier' })
    fireEvent.change(names[0]!, { target: { value: 'priority' } })
    fireEvent.change(names[1]!, { target: { value: 'priority' } })
    expect(screen.getAllByRole('alert', { name: '' })).toHaveLength(2)
    expect(screen.getAllByText('Conflicts with another row')).toHaveLength(2)

    fireEvent.click(screen.getAllByRole('button', { name: 'Remove rule' })[0]!)
    expect(screen.getAllByRole('textbox', { name: 'Service tier' })).toHaveLength(1)
    expect(screen.queryByText('Conflicts with another row')).not.toBeInTheDocument()
    expect(JSON.parse(screen.getByTestId('draft').textContent!).definition.serviceTiers).toHaveLength(1)
  })
})
