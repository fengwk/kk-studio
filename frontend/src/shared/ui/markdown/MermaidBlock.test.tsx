import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DiagramOptions } from '@fengwk/kchart'

const kchart = vi.hoisted(() => ({
  diagram: vi.fn<(source: string, options: DiagramOptions) => Promise<{ svg: string | null }>>(),
  themes: { terminal: { id: 'terminal', fg2: '#bdd7c4', style: { radius: 0, smallRadius: 0, soft: 0.12 } } },
}))

vi.mock('@fengwk/kchart', () => kchart)

beforeEach(() => {
  vi.resetModules()
  kchart.diagram.mockReset()
  kchart.diagram.mockImplementation(async (source, options) => ({
    svg: `<svg data-source="${source}" data-width="${options.width}" aria-labelledby="${options.prefix}-title"><title id="${options.prefix}-title">Diagram</title></svg>`,
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MermaidBlock', () => {
  it('renders the trimmed source at the container width in the app theme', async () => {
    // test-setup 的 ResizeObserver 立即报告 1200px；主题以 Terminal 为底、应用调色板覆盖，测量走 canvas。
    const { MermaidBlock } = await loadMermaidBlock()
    const view = render(<MermaidBlock code="  graph TD; A-->B  " />)
    await expectSvg(view.container, 'graph TD; A-->B')

    expect(kchart.diagram).toHaveBeenCalledTimes(1)
    const [source, options] = kchart.diagram.mock.calls[0]
    expect(source).toBe('graph TD; A-->B')
    expect(options).toMatchObject({
      width: 1200,
      frame: false,
      theme: { id: 'kk-studio', fg1: '#edf2ed', fg2: '#bdd7c4', style: { radius: 8, smallRadius: 6, soft: 0.12 } },
    })
    expect(options.measure?.('abc', 13, 400, 'Inter')).toBe(21)
  })

  it('uses the SVG cache when the same source mounts again', async () => {
    // 新组件实例命中模块缓存时不重算布局，但引用必须重写成新实例的 ID。
    const { MermaidBlock } = await loadMermaidBlock()
    const first = render(<MermaidBlock code="graph TD; A-->B" />)
    await expectSvg(first.container, 'graph TD; A-->B')
    const firstTitle = first.container.querySelector('.md-mermaid svg title')?.id
    expect(firstTitle).toBeTruthy()
    first.unmount()

    const second = render(<MermaidBlock code="graph TD; A-->B" />)
    await expectSvg(second.container, 'graph TD; A-->B')
    const secondTitle = second.container.querySelector('.md-mermaid svg title')?.id
    expect(secondTitle).toBeTruthy()
    expect(secondTitle).not.toBe(firstTitle)
    expect(second.container.querySelector('.md-mermaid svg')).toHaveAttribute('aria-labelledby', secondTitle)

    expect(kchart.diagram).toHaveBeenCalledTimes(1)
  })

  it('does not rerender when only surrounding whitespace changes', async () => {
    // prop 变化但 trim 后源码相同时不重新成图。
    const { MermaidBlock } = await loadMermaidBlock()
    const view = render(<MermaidBlock code="graph TD; A-->B" />)
    await expectSvg(view.container, 'graph TD; A-->B')

    view.rerender(<MermaidBlock code="  graph TD; A-->B  " />)
    await flushPromises()

    expect(kchart.diagram).toHaveBeenCalledTimes(1)
  })

  it('redraws for a new container width and waits while the container has none', async () => {
    // 隐藏容器宽度为 0 时不成图；宽度变化后按新宽度重绘，同宽度的重复通知不重复渲染。
    const callbacks: ResizeObserverCallback[] = []
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: ResizeObserverCallback) {
        callbacks.push(callback)
      }
      observe() {}
      unobserve() {}
      disconnect() {}
    })
    const resize = (width: number) => act(() => {
      for (const callback of callbacks) {
        callback([{ contentRect: { width } } as ResizeObserverEntry], {} as ResizeObserver)
      }
    })
    const { MermaidBlock } = await loadMermaidBlock()
    const view = render(<MermaidBlock code="graph TD; A-->B" />)

    resize(0)
    await flushPromises()
    expect(kchart.diagram).not.toHaveBeenCalled()

    resize(640.6)
    await expectSvg(view.container, 'data-width="640"')
    resize(640)
    resize(900)
    await expectSvg(view.container, 'data-width="900"')

    expect(kchart.diagram.mock.calls.map(([, options]) => options.width)).toEqual([640, 900])
  })

  it('opens diagram links in a new tab without opener or referrer', async () => {
    // 与 Markdown 链接一致，图内链接不能在当前页离开应用。
    kchart.diagram.mockResolvedValueOnce({ svg: '<svg><a href="https://example.com/docs"><text>Docs</text></a></svg>' })
    const { MermaidBlock } = await loadMermaidBlock()
    const view = render(<MermaidBlock code="graph TD; A-->B" />)

    await waitFor(() => {
      expect(view.container.querySelector('.md-mermaid a')).toHaveAttribute('target', '_blank')
    })
    expect(view.container.querySelector('.md-mermaid a')).toHaveAttribute('rel', 'noreferrer noopener')
  })

  it('falls back to source for blank input without loading KChart', async () => {
    // 空源码直接展示 fenced source，不触发动态模块或渲染。
    const { MermaidBlock } = await loadMermaidBlock()
    const view = render(<MermaidBlock code="   " />)

    await waitFor(() => {
      expect(view.container.querySelector('code.language-mermaid')).not.toBeNull()
    })
    expect(screen.getByRole('button', { name: '复制代码' })).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Mermaid 图表' })).not.toBeInTheDocument()
    expect(kchart.diagram).not.toHaveBeenCalled()
  })

  it.each([
    ['the diagram is invalid', () => kchart.diagram.mockResolvedValueOnce({ svg: null })],
    ['rendering rejects', () => kchart.diagram.mockRejectedValueOnce(new Error('layout failed'))],
  ])('falls back to source when %s', async (_name, fail) => {
    // 无法成图的源码保留原文，不能留下空图容器。
    fail()
    const { MermaidBlock } = await loadMermaidBlock()
    const view = render(<MermaidBlock code="not a graph" />)

    await waitFor(() => {
      expect(view.container.querySelector('code.language-mermaid')).toHaveTextContent('not a graph')
    })
    expect(screen.queryByRole('img', { name: 'Mermaid 图表' })).not.toBeInTheDocument()
  })

  it('does not cache or write SVG after unmount cancellation', async () => {
    // 未完成渲染的组件卸载后不得写 DOM/cache；后续挂载必须重新渲染。
    const deferred = createDeferred<{ svg: string }>()
    kchart.diagram
      .mockReturnValueOnce(deferred.promise)
      .mockResolvedValueOnce({ svg: '<svg data-source="second"></svg>' })
    const { MermaidBlock } = await loadMermaidBlock()
    const first = render(<MermaidBlock code="graph TD; A-->B" />)
    await waitFor(() => expect(kchart.diagram).toHaveBeenCalledTimes(1))
    first.unmount()

    deferred.resolve({ svg: '<svg data-source="cancelled"></svg>' })
    await flushPromises()

    const second = render(<MermaidBlock code="graph TD; A-->B" />)
    await expectSvg(second.container, 'second')
    expect(kchart.diagram).toHaveBeenCalledTimes(2)
  })

  it('evicts the oldest SVG after the bounded cache exceeds 48 entries', async () => {
    // cache 使用固定上限；最早源码被淘汰后再次挂载必须重新渲染。
    const { MermaidBlock } = await loadMermaidBlock()
    for (let index = 0; index < 49; index += 1) {
      const code = `graph TD; A${index}-->B${index}`
      const view = render(<MermaidBlock code={code} />)
      await expectSvg(view.container, code)
      view.unmount()
    }

    const firstAgain = render(<MermaidBlock code="graph TD; A0-->B0" />)
    await expectSvg(firstAgain.container, 'graph TD; A0-->B0')

    expect(kchart.diagram).toHaveBeenCalledTimes(50)
  })
})

async function loadMermaidBlock() {
  return import('@/shared/ui/markdown/MermaidBlock')
}

async function expectSvg(container: HTMLElement, text: string) {
  await waitFor(() => {
    expect(container.querySelector('.md-mermaid')?.innerHTML).toContain(text)
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
