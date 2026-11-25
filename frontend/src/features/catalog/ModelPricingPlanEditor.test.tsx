import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ModelPricingPlanEditor } from '@/features/catalog/ModelPricingPlanEditor'
import {
  createPricingDraftKey,
  emptyModelPricingDraft,
  type PricingChangeDraft,
  type ModelPricingDraft,
  type PricingDefinitionDraft,
  type PricingFactorsDraft,
} from '@/features/catalog/model-pricing-draft'
import type {
  ModelPricingPreviewDTO,
  ModelRateSetDTO,
} from '@/shared/api/generated/contracts'
import { setLocale } from '@/shared/i18n'

const DAY_MILLIS = 24 * 60 * 60 * 1000

/** 相对今天的 UTC 日期，用来验证“当前生效”按 UTC 日期而非本地时区判断。 */
function utcDate(offsetDays: number): string {
  return new Date(Date.now() + offsetDays * DAY_MILLIS).toISOString().slice(0, 10)
}

function factors(): PricingFactorsDraft {
  return {
    factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '',
  }
}

function pricingDefinition(input: string): PricingDefinitionDraft {
  return {
    base: { input, output: '3', cacheRead: '0.1', cacheWrite: '0.2', cacheWriteByTtl: [], reasoning: undefined },
    inputTokenRanges: [],
    serviceTiers: [],
    timePeriods: [],
  }
}

function pricedDraft(changes: PricingChangeDraft[] = []): ModelPricingDraft {
  return { definition: pricingDefinition('1'), changes }
}

function change(draftKey: string, from: string, input: string): PricingChangeDraft {
  return { draftKey, from, definition: pricingDefinition(input) }
}

function readDraft(): ModelPricingDraft {
  return JSON.parse(screen.getByTestId('draft').textContent!) as ModelPricingDraft
}

/** 服务端试算回执；费率一律由服务端给出，前端不得自行推算。 */
function previewResponse(
  rates: Partial<ModelRateSetDTO> | null,
  overrides: Partial<ModelPricingPreviewDTO> = {},
): Response {
  const body: ModelPricingPreviewDTO = {
    rates: rates
      ? { input: '1', output: '3', cacheRead: '0.1', cacheWrite: null, cacheWriteByTtl: {}, reasoning: null, ...rates }
      : null,
    activeFrom: null,
    matchedRules: [],
    ...overrides,
  }
  return new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } })
}

function Harness({ initial = emptyModelPricingDraft() }: { initial?: ModelPricingDraft }) {
  const [value, setValue] = useState(initial)
  return <><output data-testid="draft">{JSON.stringify(value)}</output><ModelPricingPlanEditor value={value} onChange={setValue} /></>
}

beforeEach(() => setLocale('en-US'))
afterEach(() => vi.unstubAllGlobals())

// Choose the exact calendar day regardless of the month in which the suite runs.
function chooseDate(trigger: HTMLElement, value: string) {
  const wanted = new Date(`${value}T12:00:00`)
  const currentValue = trigger.getAttribute('value')
  const current = currentValue ? new Date(`${currentValue}T12:00:00`) : new Date()
  const delta = (wanted.getFullYear() - current.getFullYear()) * 12 + wanted.getMonth() - current.getMonth()
  fireEvent.click(trigger)
  for (let i = 0; i < Math.abs(delta); i++) {
    fireEvent.keyDown(trigger, { key: delta < 0 ? 'PageUp' : 'PageDown' })
  }
  fireEvent.click(screen.getByRole('gridcell', { name: new Intl.DateTimeFormat(document.documentElement.lang || undefined, {
    year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
  }).format(wanted) }))
}

describe('ModelPricingPlanEditor', () => {
  it('presents unpriced rates and the fixed price sections', () => {
    render(<Harness />)
    const input = screen.getByLabelText('Input')
    expect(input).toHaveValue('')
    expect(input).toHaveAttribute('placeholder', 'Not set')
    expect(screen.getByText('USD / million tokens')).toBeInTheDocument()
    for (const heading of ['Base prices', 'Input length prices', 'Service tiers', 'Time periods', 'Rate preview']) {
      expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
    }
  })

  it('selects the plan that is currently effective in UTC and edits one plan at a time', () => {
    const past = utcDate(-5)
    const today = utcDate(0)
    const future = utcDate(5)
    render(<Harness initial={{
      definition: pricingDefinition('9'),
      changes: [
        change('past', past, '1'),
        change('today', today, '2'),
        change('future', future, '3'),
      ],
    }} />)

    // 默认选中“生效日期已到、且日期最晚”的调价，而不是基础方案或未来调价。
    const plan = screen.getByRole('button', { name: 'Price plan' })
    expect(plan).toHaveAttribute('data-value', 'today')
    expect(plan).toHaveTextContent(`${today} · Currently effective`)
    expect(screen.getByLabelText('Input')).toHaveValue('2')

    // 同一时刻只渲染一个方案编辑器。
    expect(screen.getAllByRole('heading', { name: 'Base prices' })).toHaveLength(1)
    expect(screen.getAllByLabelText('Input')).toHaveLength(1)

    // 切换方案后仍然只有一个编辑器，内容换成所选方案自己的费率。
    fireEvent.click(plan)
    fireEvent.click(screen.getByRole('option', { name: future }))
    expect(screen.getAllByRole('heading', { name: 'Base prices' })).toHaveLength(1)
    expect(screen.getAllByLabelText('Input')).toHaveLength(1)
    expect(screen.getByLabelText('Input')).toHaveValue('3')
  })

  it('clones the nearest preceding plan into a new change and keeps both plans isolated', () => {
    const past = utcDate(-5)
    const first = change('first', '2027-03-01', '2')
    first.definition.base.cacheWriteByTtl = [{ draftKey: 'source-ttl', seconds: '300', rate: '0.25' }]
    render(<Harness initial={{
      definition: pricingDefinition('1'),
      changes: [change('past', past, '7'), first],
    }} />)

    // 初始显示的是“当前生效”的历史调价，它的基础价不是新调价的克隆来源。
    expect(screen.getByLabelText('Input')).toHaveValue('7')

    fireEvent.click(screen.getByRole('button', { name: 'Add price change' }))
    chooseDate(screen.getByLabelText('Effective from (UTC)'), '2027-06-01')
    fireEvent.click(screen.getByRole('button', { name: 'Create price change' }))

    // 克隆的是“早于新日期且最晚”的调价方案，而不是当前显示的历史方案或基础方案。
    expect(screen.getByLabelText('Input')).toHaveValue('2')

    // 编辑克隆方案不会影响来源方案。
    fireEvent.change(screen.getByLabelText('Input'), { target: { value: '4' } })
    const draft = readDraft()
    expect(draft.definition.base.input).toBe('1')
    expect(draft.changes.map(({ from, definition }) => [from, definition.base.input]))
      .toEqual([[past, '7'], ['2027-03-01', '2'], ['2027-06-01', '4']])
    expect(draft.changes[2]!.definition.base.cacheWriteByTtl).toMatchObject([{ seconds: '300', rate: '0.25' }])
    expect(draft.changes[2]!.definition.base.cacheWriteByTtl[0]!.draftKey)
      .not.toBe(draft.changes[1]!.definition.base.cacheWriteByTtl[0]!.draftKey)

    // 早于全部调价的日期（早于当前生效的那条历史调价）从基础方案克隆。
    fireEvent.click(screen.getByRole('button', { name: 'Add price change' }))
    chooseDate(screen.getByLabelText('Effective from (UTC)'), utcDate(-30))
    fireEvent.click(screen.getByRole('button', { name: 'Create price change' }))
    expect(screen.getByLabelText('Input')).toHaveValue('1')
  })

  it('blocks duplicate effective dates for new drafts and for edited plans', () => {
    render(<Harness initial={pricedDraft([change('first', '2027-03-01', '2'), change('second', '2027-06-01', '3')])} />)

    // 新建调价：日期重复时不能创建，并就地提示。
    fireEvent.click(screen.getByRole('button', { name: 'Add price change' }))
    const createDate = screen.getByLabelText('Effective from (UTC)')
    chooseDate(createDate, '2027-03-01')
    expect(screen.getByRole('button', { name: 'Create price change' })).toBeDisabled()
    expect(screen.getByText('This effective date already has a plan.')).toBeInTheDocument()
    chooseDate(createDate, '2027-09-01')
    expect(screen.getByRole('button', { name: 'Create price change' })).toBeEnabled()
    expect(screen.queryByText('This effective date already has a plan.')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Add price change' }))

    // 已存在的调价改成占用别的生效日期时同样提示。
    fireEvent.click(screen.getByRole('button', { name: 'Price plan' }))
    fireEvent.click(screen.getByRole('option', { name: '2027-06-01' }))
    const editDate = screen.getByLabelText('Effective from (UTC)')
    expect(editDate).toHaveAttribute('value', '2027-06-01')
    chooseDate(editDate, '2027-03-01')
    expect(screen.getByText('This effective date already has a plan.')).toBeInTheDocument()
    expect(readDraft().changes.map(({ from }) => from)).toEqual(['2027-03-01', '2027-03-01'])
  })

  it('keeps TTL row focus while typing and reports duplicate or blank prices inline', () => {
    render(<Harness initial={pricedDraft()} />)
    const ttlRows = () => {
      // 基础价格区最后是 TTL 行：费率标签与基础 Cache Write 同名，按 DOM 顺序取 TTL 部分。
      const rates = screen.getAllByLabelText('Cache Write').slice(1)
      return screen.getAllByLabelText('TTL seconds').map((seconds, index) => ({ seconds, rate: rates[index]! }))
    }

    fireEvent.click(screen.getByRole('button', { name: 'Add TTL price' }))
    expect(ttlRows()).toHaveLength(1)
    const [firstRow] = ttlRows()
    expect(firstRow!.seconds).toHaveValue('')
    expect(screen.getByText('Enter a positive whole number of seconds.')).toBeInTheDocument()

    // 受控行以 draftKey 作为 key：输入时不重挂载，焦点与已输入内容都留在原输入框。
    firstRow!.seconds.focus()
    fireEvent.change(firstRow!.seconds, { target: { value: '300' } })
    expect(document.activeElement).toBe(firstRow!.seconds)
    expect(firstRow!.seconds).toHaveValue('300')
    expect(screen.queryByText('Enter a positive whole number of seconds.')).not.toBeInTheDocument()
    expect(screen.getByText('Enter a rate, or 0 for a zero rate.')).toBeInTheDocument()

    fireEvent.change(ttlRows()[0]!.rate, { target: { value: '0.25' } })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    // 重复秒数在两行同时提示，而不是静默合并。
    fireEvent.click(screen.getByRole('button', { name: 'Add TTL price' }))
    fireEvent.change(ttlRows()[1]!.seconds, { target: { value: '300' } })
    expect(screen.getAllByText('This TTL duration is repeated.')).toHaveLength(2)

    // 空白价按“未知”提示，且不会被当成 0。
    fireEvent.change(ttlRows()[1]!.seconds, { target: { value: '600' } })
    expect(screen.queryByText('This TTL duration is repeated.')).not.toBeInTheDocument()
    expect(screen.getByText('Enter a rate, or 0 for a zero rate.')).toBeInTheDocument()
    expect(readDraft().definition.base.cacheWriteByTtl.map(({ seconds, rate }) => [seconds, rate]))
      .toEqual([['300', '0.25'], ['600', '']])
  })

  it('offers an explicit cross-midnight split instead of silently rewriting the interval', () => {
    const initial = pricedDraft()
    initial.definition.timePeriods = [{
      draftKey: 'night', weekdays: [5], start: '22:00', end: '01:00', timezone: 'UTC', factors: factors(),
    }]
    render(<Harness initial={initial} />)

    expect(screen.getByText(/Across midnight, this becomes: Fri · 22:00–24:00 \/ Sat · 00:00–01:00/))
      .toBeInTheDocument()
    // 确认前草稿仍是单个跨午夜区间。
    expect(screen.getAllByLabelText('Start time')).toHaveLength(1)
    expect(readDraft().definition.timePeriods).toHaveLength(1)

    fireEvent.click(screen.getByRole('button', { name: 'Confirm split' }))
    const starts = screen.getAllByLabelText('Start time') as HTMLInputElement[]
    const ends = screen.getAllByLabelText('End time') as HTMLInputElement[]
    expect(starts.map((input) => input.value)).toEqual(['22:00', '00:00'])
    expect(ends.map((input) => input.value)).toEqual(['24:00', '01:00'])
    const periods = readDraft().definition.timePeriods
    expect(periods.map(({ weekdays, start, end }) => [weekdays, start, end]))
      .toEqual([[[5], '22:00', '24:00'], [[6], '00:00', '01:00']])
    expect(new Set(periods.map(({ draftKey }) => draftKey)).size).toBe(2)
    expect(screen.queryByRole('button', { name: 'Confirm split' })).not.toBeInTheDocument()
  })

  it('previews the whole draft on the server instead of computing prices locally', async () => {
    const transport = vi.fn(async (_url: string, _init: RequestInit) => previewResponse(
      { input: '3.20', output: '9.60', cacheRead: '0.1', cacheWrite: '0.2', cacheWriteByTtl: { '600': '0.4' } },
      { activeFrom: '2027-01-01', matchedRules: [{ type: 'service_tier_multiplier', serviceTier: 'priority', factor: '2' }] },
    ))
    vi.stubGlobal('fetch', transport)
    const initial = pricedDraft([change('later', '2027-01-01', '2')])
    initial.definition.base.cacheWriteByTtl = [{ draftKey: createPricingDraftKey(), seconds: '600', rate: '0.2' }]
    render(<Harness initial={initial} />)
    // 正在编辑的是基础方案，但试算必须覆盖整份草稿（含调价）。
    expect(screen.getByLabelText('Input')).toHaveValue('1')

    fireEvent.change(screen.getByLabelText('Input tokens'), { target: { value: '250000' } })
    const [date, time] = screen.getAllByRole('button', { name: 'Request start time (UTC)' })
    chooseDate(date!, '2027-01-05')
    fireEvent.click(time!)
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Hour' })).getByRole('option', { name: '02' }))
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Minute' })).getByRole('option', { name: '00' }))
    fireEvent.change(screen.getByLabelText('Returned service tier'), { target: { value: 'priority' } })
    fireEvent.click(screen.getByRole('button', { name: 'Preview prices' }))

    await waitFor(() => expect(transport).toHaveBeenCalledOnce())
    const [url, init] = transport.mock.calls[0]!
    expect(url).toBe('/api/platform/models/pricing-preview')
    expect(JSON.parse(String(init?.body))).toMatchObject({
      inputTokens: 250000,
      requestedAt: Date.parse('2027-01-05T02:00:00Z'),
      serviceTier: 'priority',
      pricing: {
        base: { input: '1', output: '3', cacheRead: '0.1', cacheWrite: '0.2', cacheWriteByTtl: { '600': '0.2' } },
        rules: [],
        changes: [{ from: '2027-01-01', base: { input: '2' }, rules: [] }],
      },
    })

    // 展示的费率来自服务端回执（$3.20），而不是本地按草稿倍率算出的价格。
    expect(await screen.findByText('Effective prices')).toBeInTheDocument()
    expect(screen.getByText(/Input \$3\.20 · Output \$9\.60 · Cache Read \$0\.1 · Cache Write \$0\.2/))
      .toBeInTheDocument()
    expect(screen.getByText('Price effective from 2027-01-01 (UTC)')).toBeInTheDocument()
    expect(screen.getByText('priority · ×2')).toBeInTheDocument()
  })

  it('drops an in-flight preview once the inputs change and ignores its late response', async () => {
    const pending: Array<{ resolve: (response: Response) => void; signal: AbortSignal }> = []
    const transport = vi.fn((_url: string, init: RequestInit) => new Promise<Response>((resolve) => {
      pending.push({ resolve, signal: init.signal as AbortSignal })
    }))
    vi.stubGlobal('fetch', transport)
    render(<Harness initial={pricedDraft()} />)
    const tokens = screen.getByLabelText('Input tokens')

    fireEvent.click(screen.getByRole('button', { name: 'Preview prices' }))
    await waitFor(() => expect(pending).toHaveLength(1))
    fireEvent.change(tokens, { target: { value: '2000' } })
    await waitFor(() => expect(pending[0]!.signal.aborted).toBe(true))

    // 迟到的旧回执不得覆盖新输入对应的结果。
    pending[0]!.resolve(previewResponse({ input: '9.99' }))
    fireEvent.click(screen.getByRole('button', { name: 'Preview prices' }))
    await waitFor(() => expect(pending).toHaveLength(2))
    pending[1]!.resolve(previewResponse({ input: '1.11' }))
    expect(await screen.findByText(/Input \$1\.11/)).toBeInTheDocument()
    expect(screen.queryByText(/\$9\.99/)).not.toBeInTheDocument()
  })

  it('does not revive an earlier preview result when inputs go A → B → A', async () => {
    let call = 0
    const transport = vi.fn(async () => {
      call += 1
      return previewResponse({ input: call === 1 ? '5.55' : '6.66' })
    })
    vi.stubGlobal('fetch', transport)
    render(<Harness initial={pricedDraft()} />)
    const tokens = screen.getByLabelText('Input tokens')

    fireEvent.click(screen.getByRole('button', { name: 'Preview prices' }))
    expect(await screen.findByText(/Input \$5\.55/)).toBeInTheDocument()

    // A → B：结果立即失效。
    fireEvent.change(tokens, { target: { value: '2000' } })
    expect(screen.queryByText(/Input \$5\.55/)).not.toBeInTheDocument()

    // B → A：回到相同输入也不复用旧结果，必须重新试算。
    fireEvent.change(tokens, { target: { value: '1000' } })
    expect(screen.queryByText(/Input \$5\.55/)).not.toBeInTheDocument()
    expect(screen.queryByText('Effective prices')).not.toBeInTheDocument()
    expect(transport).toHaveBeenCalledOnce()

    fireEvent.click(screen.getByRole('button', { name: 'Preview prices' }))
    expect(await screen.findByText(/Input \$6\.66/)).toBeInTheDocument()
  })

  it('asks for missing preview facts instead of falling back to base or free rates', async () => {
    const transport = vi.fn(async () => previewResponse(null))
    vi.stubGlobal('fetch', transport)
    render(<Harness initial={pricedDraft()} />)

    fireEvent.click(screen.getByRole('button', { name: 'Preview prices' }))
    expect(await screen.findByText('Unable to determine rates.')).toBeInTheDocument()
    expect(screen.queryByText('Effective prices')).not.toBeInTheDocument()

    const time = screen.getAllByRole('button', { name: 'Request start time (UTC)' })[0]
    const tier = screen.getByLabelText('Returned service tier')
    fireEvent.click(screen.getByRole('button', { name: 'Enter request time' }))
    expect(document.activeElement).toBe(time)
    fireEvent.click(screen.getByRole('button', { name: 'Enter service tier' }))
    expect(document.activeElement).toBe(tier)
  })

  it('sends an unconfigured draft as null pricing and reports it as unconfigured, not free', async () => {
    const transport = vi.fn(async (_url: string, _init: RequestInit) => previewResponse(null))
    vi.stubGlobal('fetch', transport)
    render(<Harness />)

    fireEvent.click(screen.getByRole('button', { name: 'Preview prices' }))
    await waitFor(() => expect(transport).toHaveBeenCalledOnce())
    // 试算结果明确报告“未配置价格”，不存在 0 价或免费兜底。
    expect(await screen.findByText('No pricing configured')).toBeInTheDocument()
    expect(screen.queryByText('Effective prices')).not.toBeInTheDocument()
    expect(JSON.parse(String(transport.mock.calls[0]![1]!.body)).pricing).toBeNull()
  })

  it('keeps reasoning on output price by default and accepts an explicit independent zero', () => {
    render(<Harness initial={pricedDraft()} />)
    const mode = screen.getByRole('button', { name: 'Independent reasoning pricing' })
    expect(mode).toHaveTextContent('Follow output price')

    fireEvent.click(mode)
    fireEvent.click(screen.getByRole('option', { name: 'Independent price' }))
    expect(readDraft().definition.base.reasoning).toBe('')
    fireEvent.change(screen.getByLabelText('Reasoning rate'), { target: { value: '0' } })
    expect(readDraft().definition.base.reasoning).toBe('0')

    fireEvent.click(mode)
    fireEvent.click(screen.getByRole('option', { name: 'Follow output price' }))
    expect(readDraft().definition.base.reasoning).toBeNull()
  })

  it('reports a token range that sets no price once its bounds hold, and clears it for an explicit zero', () => {
    render(<Harness initial={pricedDraft()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Add range' }))
    // 新行还没边界时先不报覆盖错误：与服务端“先边界、后覆盖”的校验顺序一致。
    expect(screen.queryByText('Set at least one price in this token range.')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Start (inclusive)'), { target: { value: '100' } })
    expect(screen.getByText('Set at least one price in this token range.')).toBeInTheDocument()
    expect(readDraft().definition.inputTokenRanges[0]!.rates).toEqual({})

    // 显式 0 已经是合法覆盖，行级提示立即消失；档位的 Input 排在基础价之后，按 DOM 顺序取第二个。
    fireEvent.change(screen.getAllByLabelText('Input')[1]!, { target: { value: '0' } })
    expect(screen.queryByText('Set at least one price in this token range.')).not.toBeInTheDocument()
    expect(readDraft().definition.inputTokenRanges[0]!.rates).toEqual({ input: '0' })
  })

  it('reveals the plan holding an empty-override range before the form reports its error', () => {
    const bad = change('future', utcDate(5), '2')
    bad.definition.inputTokenRanges = [{
      draftKey: 'range', minInputTokens: '0', maxInputTokensExclusive: '100', rates: {},
      cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [],
    }]
    const { container } = render(
      <form onSubmit={(event) => event.preventDefault()}>
        <Harness initial={pricedDraft([bad])} />
      </form>,
    )
    // 默认编辑基础方案，有问题的行在未来的调价方案里。
    expect(screen.getByLabelText('Input')).toHaveValue('1')
    expect(screen.queryByText('Set at least one price in this token range.')).not.toBeInTheDocument()

    fireEvent.submit(container.querySelector('form')!)
    expect(screen.getByRole('button', { name: 'Price plan' })).toHaveAttribute('data-value', 'future')
    expect(screen.getByText('Set at least one price in this token range.')).toBeInTheDocument()
  })

  it('shows duplicate service tier conflicts immediately and removes only the chosen row', () => {
    render(<Harness initial={pricedDraft()} />)
    const add = screen.getByRole('button', { name: 'Add service tier' })
    fireEvent.click(add)
    fireEvent.click(add)
    const names = screen.getAllByRole('textbox', { name: 'Service tier' })
    fireEvent.change(names[0]!, { target: { value: 'priority' } })
    fireEvent.change(names[1]!, { target: { value: 'priority' } })
    expect(screen.getAllByText('Same service tier as rule 1.')).toHaveLength(1)
    expect(screen.getAllByText('Same service tier as rule 2.')).toHaveLength(1)
    const [, keptKey] = readDraft().definition.serviceTiers.map(({ draftKey }) => draftKey)

    // 两行都存在删除入口；删除第一行只影响它自己。
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove rule' })[0]!)

    expect(screen.getAllByRole('textbox', { name: 'Service tier' })).toHaveLength(1)
    expect(screen.queryByText('Same service tier as rule 1.')).not.toBeInTheDocument()
    expect(screen.queryByText('Same service tier as rule 2.')).not.toBeInTheDocument()
    expect(readDraft().definition.serviceTiers.map(({ draftKey }) => draftKey)).toEqual([keptKey])
  })
})
