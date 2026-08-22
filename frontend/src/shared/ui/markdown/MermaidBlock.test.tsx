import { act, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mermaidApi = vi.hoisted(() => ({
  initialize: vi.fn<(config: Record<string, unknown>) => void>(),
  render: vi.fn<(id: string, text: string) => Promise<{ svg: string }>>(),
}))

vi.mock('mermaid', () => ({
  default: mermaidApi,
}))

beforeEach(() => {
  vi.resetModules()
  mermaidApi.initialize.mockReset()
  mermaidApi.render.mockReset()
  mermaidApi.render.mockImplementation(async (_id, text) => ({
    svg: `<svg data-source="${text}"></svg>`,
  }))
})

describe('MermaidBlock', () => {
  it('initializes Mermaid once and renders trimmed source with stable ids', async () => {
    // 同一模块中的多个图共享初始化，但每段源码独立 render。
    const { MermaidBlock } = await loadMermaidBlock()
    const first = render(<MermaidBlock code="  graph TD; A-->B  " />)
    await expectSvg(first.container, 'graph TD; A-->B')
    const second = render(<MermaidBlock code="sequenceDiagram; A->>B: hi" />)
    await expectSvg(second.container, 'sequenceDiagram; A->>B: hi')

    expect(mermaidApi.initialize).toHaveBeenCalledTimes(1)
    expect(mermaidApi.initialize).toHaveBeenCalledWith(expect.objectContaining({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'base',
    }))
    expect(mermaidApi.render).toHaveBeenNthCalledWith(
      1,
      expect.stringMatching(/^mmd[a-zA-Z0-9_-]+$/),
      'graph TD; A-->B',
    )
    expect(mermaidApi.render).toHaveBeenNthCalledWith(
      2,
      expect.stringMatching(/^mmd[a-zA-Z0-9_-]+$/),
      'sequenceDiagram; A->>B: hi',
    )
  })

  it('uses the SVG cache when the same source mounts again', async () => {
    // 新组件实例命中模块缓存时不得再次调用 Mermaid renderer。
    const { MermaidBlock } = await loadMermaidBlock()
    const first = render(<MermaidBlock code="graph TD; A-->B" />)
    await expectSvg(first.container, 'graph TD; A-->B')
    first.unmount()

    const second = render(<MermaidBlock code="graph TD; A-->B" />)
    await expectSvg(second.container, 'graph TD; A-->B')

    expect(mermaidApi.render).toHaveBeenCalledTimes(1)
  })

  it('does not rerender when only surrounding whitespace changes', async () => {
    // prop 变化但 trim 后源码相同时命中组件内 done guard。
    const { MermaidBlock } = await loadMermaidBlock()
    const view = render(<MermaidBlock code="graph TD; A-->B" />)
    await expectSvg(view.container, 'graph TD; A-->B')

    view.rerender(<MermaidBlock code="  graph TD; A-->B  " />)
    await act(async () => {
      await Promise.resolve()
    })

    expect(mermaidApi.render).toHaveBeenCalledTimes(1)
  })

  it('falls back to source for blank input without loading Mermaid', async () => {
    // 空源码直接展示 fenced source，不触发动态模块或 render。
    const { MermaidBlock } = await loadMermaidBlock()
    const view = render(<MermaidBlock code="   " />)

    await waitFor(() => {
      expect(view.container.querySelector('code.language-mermaid')).not.toBeNull()
    })
    expect(screen.getByRole('button', { name: '复制代码' })).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Mermaid 图表' })).not.toBeInTheDocument()
    expect(mermaidApi.initialize).not.toHaveBeenCalled()
    expect(mermaidApi.render).not.toHaveBeenCalled()
  })

  it('falls back to source when Mermaid rendering rejects', async () => {
    // 非法 Mermaid 源码保留原文，不能留下空图容器。
    mermaidApi.render.mockRejectedValueOnce(new Error('parse failed'))
    const { MermaidBlock } = await loadMermaidBlock()
    const view = render(<MermaidBlock code="not a graph" />)

    await waitFor(() => {
      expect(view.container.querySelector('code.language-mermaid')).toHaveTextContent('not a graph')
    })
    expect(screen.queryByRole('img', { name: 'Mermaid 图表' })).not.toBeInTheDocument()
  })

  it('does not cache or write SVG after unmount cancellation', async () => {
    // 未完成 render 的组件卸载后不得写 DOM/cache；后续挂载必须重新 render。
    const deferred = createDeferred<{ svg: string }>()
    mermaidApi.render
      .mockReturnValueOnce(deferred.promise)
      .mockResolvedValueOnce({ svg: '<svg data-source="second"></svg>' })
    const { MermaidBlock } = await loadMermaidBlock()
    const first = render(<MermaidBlock code="graph TD; A-->B" />)
    await waitFor(() => expect(mermaidApi.render).toHaveBeenCalledTimes(1))
    first.unmount()

    deferred.resolve({ svg: '<svg data-source="cancelled"></svg>' })
    await flushPromises()

    const second = render(<MermaidBlock code="graph TD; A-->B" />)
    await expectSvg(second.container, 'second')
    expect(mermaidApi.render).toHaveBeenCalledTimes(2)
  })

  it('evicts the oldest SVG after the bounded cache exceeds 48 entries', async () => {
    // cache 使用固定上限；最早源码被淘汰后再次挂载必须重新 render。
    const { MermaidBlock } = await loadMermaidBlock()
    for (let index = 0; index < 49; index += 1) {
      const code = `graph TD; A${index}-->B${index}`
      const view = render(<MermaidBlock code={code} />)
      await expectSvg(view.container, code)
      view.unmount()
    }

    const firstAgain = render(<MermaidBlock code="graph TD; A0-->B0" />)
    await expectSvg(firstAgain.container, 'graph TD; A0-->B0')

    expect(mermaidApi.render).toHaveBeenCalledTimes(50)
  })
})

async function loadMermaidBlock() {
  return import('@/shared/ui/markdown/MermaidBlock')
}

async function expectSvg(container: HTMLElement, source: string) {
  await waitFor(() => {
    expect(container.querySelector('.md-mermaid')?.innerHTML).toContain(source)
  })
}

async function flushPromises() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

function createDeferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}
