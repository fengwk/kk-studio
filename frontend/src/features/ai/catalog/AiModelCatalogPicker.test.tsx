import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  ModelDefaultCandidateDTO,
  ModelDefaultsPageDTO,
} from '@/shared/api/contracts/ai-catalog'
import { ModelCatalogPicker } from '@/features/ai/catalog/AiModelCatalogPicker'
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
    supportIssues: [],
    pricingSupported: false,
  }
}

function page(
  source: ModelDefaultsPageDTO['source'],
  generation: string,
  items: ModelDefaultCandidateDTO[],
): ModelDefaultsPageDTO {
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
    const first = deferred<ModelDefaultsPageDTO>()
    const stale = deferred<ModelDefaultsPageDTO>()
    const current = deferred<ModelDefaultsPageDTO>()
    const crossSource = deferred<ModelDefaultsPageDTO>()
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
})
