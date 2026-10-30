import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  ModelCatalogPageDTO,
  ModelDefaultCandidateDTO,
} from '@/shared/api/generated/contracts'
import { ModelCatalogPicker } from '@/features/catalog/AiModelCatalogPicker'
import { agentService } from '@/shared/api/agent-service'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listModelDefaults: vi.fn(),
  },
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((next) => {
    resolve = next
  })
  return { promise, resolve }
}

function candidate(id: string): ModelDefaultCandidateDTO {
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
  source: ModelCatalogPageDTO['source'],
  generation: string,
  items: ModelDefaultCandidateDTO[],
): ModelCatalogPageDTO {
  return { source, generation, loading: false, items, nextCursor: null }
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
    const crossSource = deferred<ModelCatalogPageDTO>()
    vi.mocked(agentService.listModelDefaults)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(stale.promise)
      .mockReturnValueOnce(current.promise)
      .mockReturnValueOnce(crossSource.promise)

    render(<ModelCatalogPicker onApply={vi.fn()} />)
    await act(async () => {
      vi.advanceTimersByTime(250)
    })
    await act(async () => {
      first.resolve(page('models_dev_v1', 'gen-1', [candidate('initial')]))
      await first.promise
    })
    expect(screen.getByText('catalog-provider / initial')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'second' } })
    await act(async () => {
      vi.advanceTimersByTime(250)
    })
    expect(agentService.listModelDefaults).toHaveBeenCalledTimes(2)

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'third' } })
    await act(async () => {
      vi.advanceTimersByTime(250)
    })
    expect(agentService.listModelDefaults).toHaveBeenCalledTimes(3)
    await act(async () => {
      current.resolve(page('models_dev_v2', 'gen-2', [candidate('refreshed')]))
      await current.promise
    })
    expect(screen.getByText('catalog-provider / refreshed')).toBeInTheDocument()

    // 旧 query 的响应在新请求之后才返回：epoch 已过期，不得覆盖较新的结果。
    await act(async () => {
      stale.resolve(page('models_dev_v1', 'gen-1', [candidate('stale')]))
      await stale.promise
    })
    expect(screen.queryByText('catalog-provider / stale')).not.toBeInTheDocument()
    expect(screen.getByText('catalog-provider / refreshed')).toBeInTheDocument()

    // 同一 generation 号来自不同 source 时仍视为混源响应，不能把两个目录拼在一起。
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'fourth' } })
    await act(async () => {
      vi.advanceTimersByTime(250)
    })
    expect(agentService.listModelDefaults).toHaveBeenCalledTimes(4)
    await act(async () => {
      crossSource.resolve(page('models_dev_v1', 'gen-2', [candidate('mixed')]))
      await crossSource.promise
    })
    expect(screen.queryByText('catalog-provider / mixed')).not.toBeInTheDocument()
    expect(screen.getByText('catalog-provider / refreshed')).toBeInTheDocument()
  })

  it('renders mapped support issues in candidate preview and invokes onApply on fill', async () => {
    setLocale('zh-CN')
    vi.useFakeTimers()
    const item: ModelDefaultCandidateDTO = {
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
    vi.mocked(agentService.listModelDefaults).mockResolvedValueOnce(
      page('models_dev_v2', 'gen-1', [item]),
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
    const unsupportedItem: ModelDefaultCandidateDTO = {
      ...candidate('unsupported-model'),
      supportIssues: [
        'ignored region multiplier',
        'non-token cost is not included: web_search',
      ],
      pricingSupported: false,
    }
    vi.mocked(agentService.listModelDefaults).mockResolvedValueOnce(
      page('models_dev_v2', 'gen-1', [unsupportedItem]),
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
})
