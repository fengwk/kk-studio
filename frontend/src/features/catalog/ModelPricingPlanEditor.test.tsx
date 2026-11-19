import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ModelPricingPlanEditor } from '@/features/catalog/ModelPricingPlanEditor'
import { emptyModelPricingDraft, type ModelPricingDraft } from '@/features/catalog/model-pricing-draft'
import { setLocale } from '@/shared/i18n'

function Harness({ initial = emptyModelPricingDraft() }: { initial?: ModelPricingDraft }) {
  const [value, setValue] = useState(initial)
  return <><output data-testid="draft">{JSON.stringify(value)}</output><ModelPricingPlanEditor value={value} onChange={setValue} /></>
}

afterEach(() => vi.unstubAllGlobals())

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

  it('shows that the default reasoning rate follows output and accepts an explicit independent zero', () => {
    setLocale('en-US')
    render(<Harness />)
    const mode = screen.getByRole('button', { name: 'Independent reasoning pricing' })
    expect(mode).toHaveTextContent('Follow output price')
    fireEvent.click(mode)
    fireEvent.click(screen.getByRole('option', { name: 'Independent price' }))
    expect(screen.getByLabelText('Reasoning')).toHaveValue('')
    fireEvent.change(screen.getByLabelText('Reasoning'), { target: { value: '0' } })
    expect(JSON.parse(screen.getByTestId('draft').textContent!).definition.base.reasoning).toBe('0')
    fireEvent.click(mode)
    fireEvent.click(screen.getByRole('option', { name: 'Follow output price' }))
    expect(JSON.parse(screen.getByTestId('draft').textContent!).definition.base.reasoning).toBeNull()
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
    expect(screen.getAllByLabelText('End time').map((input) => input.getAttribute('value')))
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

  it('uses the server preview request and renders the server-computed rates and matches', async () => {
    setLocale('en-US')
    const transport = vi.fn(async (_url: string, _init: RequestInit) => new Response(JSON.stringify({
      rates: { input: '3.20', output: '9.60', cacheRead: '0.1', cacheWrite: null, cacheWriteByTtl: {}, reasoning: null },
      activeFrom: '2027-01-01',
      matchedRules: [{ type: 'service_tier_multiplier', serviceTier: 'priority', factor: '2' }],
    }), { headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', transport)
    const initial = emptyModelPricingDraft()
    initial.definition.base.input = '1'
    initial.definition.base.output = '3'
    initial.definition.base.cacheRead = '0.1'
    render(<Harness initial={initial} />)

    fireEvent.change(screen.getByLabelText('Input tokens'), { target: { value: '250000' } })
    fireEvent.change(screen.getByLabelText('Request start time (UTC)'), { target: { value: '2027-01-05T02:00' } })
    fireEvent.change(screen.getByLabelText('Returned service tier'), { target: { value: 'priority' } })
    fireEvent.click(screen.getByRole('button', { name: 'Preview prices' }))

    await waitFor(() => expect(transport).toHaveBeenCalledOnce())
    const [url, init] = transport.mock.calls[0]!
    expect(url).toBe('/api/platform/models/pricing-preview')
    expect(JSON.parse(String(init?.body))).toMatchObject({
      inputTokens: 250000,
      requestedAt: Date.parse('2027-01-05T02:00:00Z'),
      serviceTier: 'priority',
      pricing: { base: { input: '1', output: '3' } },
    })
    expect(await screen.findByText(/Input \$3.20 · Output \$9.60/)).toBeInTheDocument()
    expect(screen.getByText(/priority · ×2/)).toBeInTheDocument()
  })
})
