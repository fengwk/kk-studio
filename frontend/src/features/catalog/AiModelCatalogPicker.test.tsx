import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  ModelCatalogPageDTO,
  ModelCatalogCandidateDTO,
} from '@/shared/api/generated/contracts'
import { ModelCatalogPicker } from '@/features/catalog/AiModelCatalogPicker'
import { agentService } from '@/shared/api/agent-service'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listModelCatalog: vi.fn(),
    refreshModelCatalog: vi.fn(),
  },
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((next) => {
    resolve = next
  })
  return { promise, resolve }
}

function candidate(id: string): ModelCatalogCandidateDTO {
  return {
    id,
    provider: 'catalog-provider',
    modelId: `wire-${id}`,
    name: id,
    abilities: {},
    supportIssues: [],
    pricingSupported: false,
  }
}

function page(
  generation: string,
  items: ModelCatalogCandidateDTO[],
): ModelCatalogPageDTO {
  return { generation, loading: false, items, nextCursor: null }
}

describe('ModelCatalogPicker request fencing', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('drops a late older-generation response after a newer query wins', async () => {
    setLocale('zh-CN')
    vi.useFakeTimers()
    const first = deferred<ModelCatalogPageDTO>()
    const stale = deferred<ModelCatalogPageDTO>()
    const current = deferred<ModelCatalogPageDTO>()
    vi.mocked(agentService.listModelCatalog)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(stale.promise)
      .mockReturnValueOnce(current.promise)

    render(<ModelCatalogPicker onApply={vi.fn()} />)
    await act(async () => {
      vi.advanceTimersByTime(250)
    })
    await act(async () => {
      first.resolve(page('gen-1', [candidate('initial')]))
      await first.promise
    })
    expect(screen.getByText('catalog-provider / initial')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'second' } })
    await act(async () => {
      vi.advanceTimersByTime(250)
    })
    expect(agentService.listModelCatalog).toHaveBeenCalledTimes(2)

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'third' } })
    await act(async () => {
      vi.advanceTimersByTime(250)
    })
    expect(agentService.listModelCatalog).toHaveBeenCalledTimes(3)
    await act(async () => {
      current.resolve(page('gen-2', [candidate('refreshed')]))
      await current.promise
    })
    expect(screen.getByText('catalog-provider / refreshed')).toBeInTheDocument()

    // 旧 query 的响应在新请求之后才返回：epoch 已过期，不得覆盖较新的结果。
    await act(async () => {
      stale.resolve(page('gen-1', [candidate('stale')]))
      await stale.promise
    })
    expect(screen.queryByText('catalog-provider / stale')).not.toBeInTheDocument()
    expect(screen.getByText('catalog-provider / refreshed')).toBeInTheDocument()
  })

  it('renders mapped support issues in candidate preview and invokes onApply on fill', async () => {
    setLocale('zh-CN')
    vi.useFakeTimers()
    const item: ModelCatalogCandidateDTO = {
      ...candidate('warned-model'),
      supportIssues: [
        'ignored region multiplier',
        'non-token cost is not included: web_search',
        'custom backend issue',
      ],
      pricingSupported: true,
      pricing: {
        base: { input: '1', output: '2', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
        overrides: [],
        multipliers: [],
      },
    }
    vi.mocked(agentService.listModelCatalog).mockResolvedValueOnce(
      page('gen-1', [item]),
    )
    const onApply = vi.fn()

    render(<ModelCatalogPicker onApply={onApply} />)
    await act(async () => {
      vi.advanceTimersByTime(250)
    })

    const titleButton = screen.getByRole('button', { name: /catalog-provider \/ warned-model/ })
    fireEvent.click(titleButton)

    expect(screen.getByText('已忽略地区倍率，统计费用可能低于实际账单')).toBeInTheDocument()
    expect(screen.getByText('网页搜索附加费用未计入Token价格')).toBeInTheDocument()
    expect(screen.getByText('custom backend issue')).toBeInTheDocument()

    // 填入时保留完整 candidate 参数交由 apply 消费
    fireEvent.click(screen.getByRole('button', { name: '一键填入' }))
    expect(onApply).toHaveBeenCalledWith(item)
  })

  it('renders clear warning notices in en-US preview and indicates unsupported pricing', async () => {
    setLocale('en-US')
    vi.useFakeTimers()
    const unsupportedItem: ModelCatalogCandidateDTO = {
      ...candidate('unsupported-model'),
      supportIssues: [
        'ignored region multiplier',
        'non-token cost is not included: web_search',
      ],
      pricingSupported: false,
    }
    vi.mocked(agentService.listModelCatalog).mockResolvedValueOnce(
      page('gen-1', [unsupportedItem]),
    )

    render(<ModelCatalogPicker onApply={vi.fn()} />)
    await act(async () => {
      vi.advanceTimersByTime(250)
    })

    const titleButton = screen.getByRole('button', { name: /catalog-provider \/ unsupported-model/ })
    fireEvent.click(titleButton)

    expect(
      screen.getByText('Region multipliers ignored; estimated costs may be lower than your actual bill'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Web search surcharges are not included in token prices'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Pricing is not fully supported; existing prices are kept.'),
    ).toBeInTheDocument()
  })

  it('resets items without cross-generation appending when generation changes on loadMore', async () => {
    setLocale('zh-CN')
    vi.useFakeTimers()
    const initialPage = {
      ...page('gen-1', [candidate('m1')]),
      nextCursor: 'cursor-1',
    }
    const nextGenPage = page('gen-2', [candidate('m2')])
    vi.mocked(agentService.listModelCatalog)
      .mockResolvedValueOnce(initialPage)
      .mockResolvedValueOnce(nextGenPage)

    render(<ModelCatalogPicker onApply={vi.fn()} />)
    await act(async () => {
      vi.advanceTimersByTime(250)
    })
    expect(screen.getByText('catalog-provider / m1')).toBeInTheDocument()

    // 点击加载更多，后端返回新 generation 的内容
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    await act(async () => {
      await Promise.resolve()
    })

    // generation 改变，重置列表，不与旧代际混合
    expect(screen.queryByText('catalog-provider / m1')).not.toBeInTheDocument()
    expect(screen.getByText('catalog-provider / m2')).toBeInTheDocument()
  })

  it('reloads the filtered first page after refresh instead of adopting the unfiltered response', async () => {
    setLocale('zh-CN')
    vi.mocked(agentService.listModelCatalog)
      .mockResolvedValueOnce({ ...page('gen-1', [candidate('m1')]), nextCursor: 'cursor-m1' })
      .mockResolvedValueOnce({ ...page('gen-1', [candidate('alpha-1')]), nextCursor: 'cursor-alpha-1' })
      .mockResolvedValueOnce({ ...page('gen-2', [candidate('alpha-2')]), nextCursor: 'cursor-alpha-2' })
      .mockResolvedValueOnce(page('gen-2', [candidate('alpha-3')]))
    vi.mocked(agentService.refreshModelCatalog).mockResolvedValue({
      ...page('gen-2', [candidate('unfiltered')]),
      nextCursor: 'cursor-unfiltered',
    })

    render(<ModelCatalogPicker onApply={vi.fn()} />)
    expect(await screen.findByText('catalog-provider / m1')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'alpha' } })
    expect(await screen.findByText('catalog-provider / alpha-1')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '刷新' }))
    expect(await screen.findByText('catalog-provider / alpha-2')).toBeInTheDocument()
    // 刷新响应不带 query：它的首页条目与游标都不能进入筛选结果，成功后按当前搜索词重取首页。
    expect(screen.queryByText('catalog-provider / unfiltered')).not.toBeInTheDocument()
    expect(agentService.listModelCatalog).toHaveBeenNthCalledWith(3, 'alpha', null, 20)

    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    expect(await screen.findByText('catalog-provider / alpha-3')).toBeInTheDocument()
    // load-more 沿用当前搜索词和筛选页游标，不能拼上刷新响应的未筛选游标。
    expect(agentService.listModelCatalog).toHaveBeenLastCalledWith('alpha', 'cursor-alpha-2', 20)
  })

  it('drops the previous cursor when a new search first page fails', async () => {
    setLocale('zh-CN')
    vi.mocked(agentService.listModelCatalog)
      .mockResolvedValueOnce(page('gen-1', [candidate('m1')]))
      .mockResolvedValueOnce({ ...page('gen-1', [candidate('alpha')]), nextCursor: 'cursor-alpha' })
      .mockRejectedValueOnce(new Error('catalog unavailable'))

    render(<ModelCatalogPicker onApply={vi.fn()} />)
    expect(await screen.findByText('catalog-provider / m1')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'alpha' } })
    expect(await screen.findByText('catalog-provider / alpha')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '加载更多' })).toBeInTheDocument()

    // 首页失败时错误可见，但 alpha 的游标必须已经作废：不会再被拼到 beta 上。
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'beta' } })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    expect(agentService.listModelCatalog).toHaveBeenCalledTimes(3)
    expect(agentService.listModelCatalog).not.toHaveBeenCalledWith('beta', 'cursor-alpha', 20)
  })

  it('keeps the newer search result when the refresh response arrives after it started', async () => {
    setLocale('zh-CN')
    const refreshResponse = deferred<ModelCatalogPageDTO>()
    const searchPage = deferred<ModelCatalogPageDTO>()
    vi.mocked(agentService.listModelCatalog)
      .mockResolvedValueOnce(page('gen-1', [candidate('base')]))
      .mockReturnValueOnce(searchPage.promise)
    vi.mocked(agentService.refreshModelCatalog).mockReturnValue(refreshResponse.promise)

    render(<ModelCatalogPicker onApply={vi.fn()} />)
    expect(await screen.findByText('catalog-provider / base')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '刷新' }))
    expect(agentService.refreshModelCatalog).toHaveBeenCalledTimes(1)

    // 刷新 POST 在途时用户改搜索词：防抖发起的新首页请求持有更新的 epoch。
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'beta' } })
    await waitFor(() => expect(agentService.listModelCatalog).toHaveBeenCalledTimes(2))

    await act(async () => {
      refreshResponse.resolve(page('gen-2', [candidate('unfiltered')]))
      await refreshResponse.promise
    })
    // 迟到的刷新响应不得再触发 reload，也不得覆盖用户的搜索请求。
    expect(agentService.listModelCatalog).toHaveBeenCalledTimes(2)

    await act(async () => {
      searchPage.resolve(page('gen-2', [candidate('beta')]))
      await searchPage.promise
    })
    expect(screen.getByText('catalog-provider / beta')).toBeInTheDocument()
    expect(screen.queryByText('catalog-provider / unfiltered')).not.toBeInTheDocument()
  })
})
