import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ChartRenderResult,
  ChartSpec,
  DiagramDiagnostic,
  DiagramOptions,
  RenderOptions,
  RenderResult,
  Scene,
} from '@fengwk/kchart'

type DiagramResult = RenderResult<DiagramDiagnostic>
type AttachTarget = Parameters<typeof import('@fengwk/kchart').attach>[0]

const kchart = vi.hoisted(() => ({
  render: vi.fn<(spec: ChartSpec, options: RenderOptions) => Promise<ChartRenderResult>>(),
  diagram: vi.fn<(source: string, options: DiagramOptions) => Promise<DiagramResult>>(),
  attach: vi.fn<(svg: AttachTarget, spec?: ChartSpec) => Promise<() => void>>(),
  themes: { terminal: { id: 'terminal', fg2: '#bdd7c4', style: { radius: 0, smallRadius: 0, soft: 0.12 } } },
}))

vi.mock('@fengwk/kchart', () => kchart)

const CHART_SPEC: ChartSpec = {
  chart: 'bar',
  title: '发布节奏',
  subtitle: '每周完成量',
  source: '发布记录',
  note: '仅统计已完成项',
  data: [
    { region: '华东', orders: 120 },
    { region: '华南', orders: 85 },
  ],
  encode: { category: 'region', value: 'orders' },
}

const DATA_TABLE = {
  columns: [
    { key: 'region', label: '地区' },
    { key: 'orders', label: '订单' },
  ],
  rows: [
    [{ text: '华东', value: 'East' }, { text: '120', value: 120 }],
    [{ text: '华南', value: 'South' }, { text: '85', value: 85 }],
  ],
}

beforeEach(() => {
  vi.resetModules()
  kchart.render.mockReset()
  kchart.diagram.mockReset()
  kchart.attach.mockReset()
  kchart.render.mockImplementation(async (spec, options) => ({
    svg: `<svg data-chart="${String(spec.chart)}" data-width="${options.width}" aria-labelledby="${options.prefix}-title"><title id="${options.prefix}-title">Chart</title></svg>`,
    scene: sceneOf(spec),
    table: null,
    diagnostics: [],
  }))
  kchart.diagram.mockImplementation(async (source, options) => ({
    svg: `<svg data-source="${source}" data-width="${options.width}" aria-labelledby="${options.prefix}-title"><title id="${options.prefix}-title">Diagram</title></svg>`,
    scene: { width: 100, height: 100, children: [] },
    diagnostics: [],
  }))
  kchart.attach.mockImplementation(async () => () => {})
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('VizBlock', () => {
  it('renders a structure diagram with syntax, compact layout and the app theme', async () => {
    // test-setup 的 ResizeObserver 立即报告 1200px；主题以 Terminal 为底、应用调色板覆盖，测量走 canvas。
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="mermaid" code="  graph TD; A-->B  " />)
    await expectSvg(view.container, 'graph TD; A-->B')

    expect(kchart.diagram).toHaveBeenCalledTimes(1)
    const [source, options] = kchart.diagram.mock.calls[0]
    expect(source).toBe('graph TD; A-->B')
    expect(options).toMatchObject({
      syntax: 'mermaid',
      width: 720,
      compact: true,
      frame: false,
      theme: { id: 'kk-studio', fg1: '#edf2ed', fg2: '#bdd7c4', style: { radius: 8, smallRadius: 6, soft: 0.12 } },
    })
    expect(options.measure?.('abc', 13, 400, 'Inter')).toBe(21)
  })

  it.each(['layers', 'blocks', 'swimlane'] as const)('routes %s sources through diagram with that syntax', async (syntax) => {
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax={syntax} code="row" />)
    await expectSvg(view.container, 'data-source="row"')
    expect(kchart.diagram).toHaveBeenCalledWith('row', expect.objectContaining({ syntax }))
  })

  it('renders chart title, subtitle, source and note around the SVG', async () => {
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code={JSON.stringify(CHART_SPEC)} />)
    await expectSvg(view.container, 'data-chart="bar"')

    expect(kchart.render).toHaveBeenCalledTimes(1)
    expect(kchart.render.mock.calls[0]?.[0]).toEqual(CHART_SPEC)
    expect(kchart.render.mock.calls[0]?.[1]).toMatchObject({
      width: 720,
      lang: 'zh-CN',
      frame: false,
      theme: { id: 'kk-studio' },
    })
    expect(screen.getByText('发布节奏')).toHaveClass('md-viz-heading')
    expect(screen.getByText('每周完成量')).toHaveClass('md-viz-subtitle')
    expect(screen.getByText('来源：发布记录')).toBeInTheDocument()
    expect(screen.getByText('注：仅统计已完成项')).toBeInTheDocument()
    expect(view.container.querySelector('details.md-viz-data')).toBeNull()
  })

  it('shows a collapsed data table with its row count and Markdown table structure', async () => {
    kchart.render.mockImplementationOnce(async (spec, options) => ({
      svg: `<svg data-chart="bar" data-width="${options.width}"></svg>`,
      scene: sceneOf(spec),
      table: DATA_TABLE,
      diagnostics: [],
    }))
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code={JSON.stringify(CHART_SPEC)} />)
    await expectSvg(view.container, 'data-chart="bar"')

    const details = view.container.querySelector('details.md-viz-data')
    expect(details).not.toBeNull()
    expect(details).not.toHaveAttribute('open')
    const summary = details?.querySelector('summary')
    expect(summary).toHaveTextContent('图表数据 · 2 行')
    expect(details?.querySelectorAll('thead th')).toHaveLength(2)
    expect(details?.querySelectorAll('tbody tr')).toHaveLength(2)
    expect(details?.querySelector('tbody tr td')).toHaveTextContent('华东')

    if (summary) {
      fireEvent.click(summary)
    }
    expect(details).toHaveAttribute('open')
  })

  it('does not render an empty data table', async () => {
    kchart.render.mockImplementationOnce(async (spec, options) => ({
      svg: `<svg data-chart="bar" data-width="${options.width}"></svg>`,
      scene: sceneOf(spec),
      table: { columns: [], rows: [] },
      diagnostics: [],
    }))
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code={JSON.stringify(CHART_SPEC)} />)
    await expectSvg(view.container, 'data-chart="bar"')
    expect(view.container.querySelector('details.md-viz-data')).toBeNull()
  })

  it('uses the SVG cache when the same source and syntax mount again', async () => {
    // 新组件实例命中模块缓存时不重算布局，但引用必须重写成新实例的 ID。
    const { VizBlock } = await loadVizBlock()
    const first = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)
    await expectSvg(first.container, 'graph TD; A-->B')
    const firstTitle = first.container.querySelector('.md-viz svg title')?.id
    expect(firstTitle).toBeTruthy()
    first.unmount()

    const second = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)
    await expectSvg(second.container, 'graph TD; A-->B')
    const secondTitle = second.container.querySelector('.md-viz svg title')?.id
    expect(secondTitle).toBeTruthy()
    expect(secondTitle).not.toBe(firstTitle)
    expect(second.container.querySelector('.md-viz svg')).toHaveAttribute('aria-labelledby', secondTitle)

    expect(kchart.diagram).toHaveBeenCalledTimes(1)
  })

  it('keys the cache by syntax and language', async () => {
    const { setLocale } = await import('@/shared/i18n')
    const { VizBlock } = await loadVizBlock()
    const first = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)
    await expectSvg(first.container, 'graph TD; A-->B')
    first.unmount()

    const otherSyntax = render(<VizBlock syntax="blocks" code="graph TD; A-->B" />)
    await expectSvg(otherSyntax.container, 'data-source="graph TD; A-->B"')
    otherSyntax.unmount()
    expect(kchart.diagram).toHaveBeenCalledTimes(2)

    setLocale('en-US')
    const otherLanguage = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)
    await expectSvg(otherLanguage.container, 'data-source="graph TD; A-->B"')
    expect(kchart.diagram).toHaveBeenCalledTimes(3)
  })

  it('redraws and caches by layout width, without mounting again above 720px', async () => {
    // 验证隐藏、窄容器与宽容器的转换；宽容器之间保持同一 SVG 节点和缓存。
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
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)

    resize(0)
    await flushPromises()
    expect(kchart.diagram).not.toHaveBeenCalled()

    resize(640.6)
    await expectSvg(view.container, 'data-width="640"')
    resize(640)
    resize(900)
    await expectSvg(view.container, 'data-width="720"')
    const wideSvg = view.container.querySelector('svg[data-width="720"]')
    resize(1200)
    await flushPromises()
    expect(view.container.querySelector('svg[data-width="720"]')).toBe(wideSvg)

    view.unmount()
    const next = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)
    resize(1000)
    await expectSvg(next.container, 'data-width="720"')
    expect(kchart.diagram.mock.calls.map(([, options]) => options.width)).toEqual([640, 720])
  })

  it('opens diagram links in a new tab without opener or referrer', async () => {
    // 与 Markdown 链接一致，图内链接不能在当前页离开应用。
    kchart.diagram.mockResolvedValueOnce({
      svg: '<svg><a href="https://example.com/docs"><text>Docs</text></a></svg>',
      scene: { width: 100, height: 100, children: [] },
      diagnostics: [],
    })
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)

    await waitFor(() => {
      expect(view.container.querySelector('.md-viz a')).toHaveAttribute('target', '_blank')
    })
    expect(view.container.querySelector('.md-viz a')).toHaveAttribute('rel', 'noreferrer noopener')
  })

  it('falls back to source for blank input without loading KChart', async () => {
    // 空源码直接展示 fenced source，不触发动态模块或渲染。
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code="   " />)

    expect(view.container.querySelector('code.language-kchart')).not.toBeNull()
    expect(screen.getByRole('button', { name: '复制代码' })).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: '图表' })).not.toBeInTheDocument()
    expect(kchart.render).not.toHaveBeenCalled()
    expect(kchart.diagram).not.toHaveBeenCalled()
  })

  it('keeps an invalid JSON declaration as source and shows the parse error', async () => {
    const source = '{"chart": "bar",}'
    let detail = ''
    try {
      JSON.parse(source)
    } catch (error) {
      detail = error instanceof Error ? error.message : String(error)
    }
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code={source} />)

    await waitFor(() => {
      expect(view.container.querySelector('code.language-kchart')).toHaveTextContent('"chart": "bar"')
    })
    expect(screen.getByText(`无法渲染此图。${detail}`)).toBeInTheDocument()
    expect(kchart.render).not.toHaveBeenCalled()
  })

  it('shows the first chart validation diagnostic with its path, message and fix', async () => {
    // 校验诊断不是运行异常：展示 KChart 给出的位置与改法，后续诊断不重复堆叠。
    kchart.render.mockResolvedValueOnce({
      svg: null,
      scene: null,
      table: null,
      diagnostics: [
        { code: 'BAD_CHART', message: '缺少数据', fix: '补充 data', path: '/data' },
        { code: 'LATER', message: 'later error', fix: 'later fix' },
      ],
    })
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code={JSON.stringify(CHART_SPEC)} />)

    await waitFor(() => {
      expect(view.container.querySelector('code.language-kchart')).not.toBeNull()
    })
    expect(screen.getByText('无法渲染此图。/data: 缺少数据。补充 data。')).toBeInTheDocument()
    expect(screen.queryByText(/later error/)).not.toBeInTheDocument()
  })

  it.each([
    ['zh-CN', '无法渲染此图。第 2 行：'],
    ['en-US', "Can't render this figure. Line 2: "],
  ] as const)('keeps the first diagram error as literal text with a %s UI lead-in', async (locale, lead) => {
    // 诊断自身不随 UI 语言翻译，只按其语言补句末标点；警告和后续错误不显示，标签按纯文本转义。
    const { setLocale } = await import('@/shared/i18n')
    setLocale(locale)
    const diagnostic = { code: 'invalid', line: 2, column: 1, topic: 'flowchart', severity: 'error' as const, message: '语法错误 <b>节点</b>', fix: '请补全 <em>节点</em>' }
    kchart.diagram.mockResolvedValueOnce({
      svg: null,
      scene: null,
      diagnostics: [
        { ...diagnostic, severity: 'warning', message: 'warning' },
        diagnostic,
        { ...diagnostic, message: 'later error' },
      ],
    })
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="mermaid" code={'flowchart TD\nnot a graph'} />)

    const note = await screen.findByText(`${lead}${diagnostic.message}。${diagnostic.fix}。`)
    expect(note.querySelector('b, em')).toBeNull()
    expect(view.container.querySelector('code.language-mermaid')).toHaveTextContent('not a graph')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('joins an English diagnostic as sentences after the lead-in', async () => {
    // 西文诊断补句点并以空格分隔，与中文界面的前导语直接相接。
    kchart.diagram.mockResolvedValueOnce({
      svg: null,
      scene: null,
      diagnostics: [{ code: 'BAD_DIAGRAM', line: 7, column: 1, topic: 'block', severity: 'error', message: 'Composite block is missing end', fix: 'Put end on its own line' }],
    })
    const { VizBlock } = await loadVizBlock()
    render(<VizBlock syntax="blocks" code="block-beta" />)

    expect(await screen.findByText('无法渲染此图。第 7 行：Composite block is missing end. Put end on its own line.')).toBeInTheDocument()
  })

  it('retains the source and shows only the lead-in when rendering rejects', async () => {
    // 运行异常不是 KChart 诊断，不向用户显示内部异常文本。
    kchart.diagram.mockRejectedValueOnce(new Error('layout failed'))
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="mermaid" code="not a graph" />)

    await waitFor(() => {
      expect(view.container.querySelector('code.language-mermaid')).toHaveTextContent('not a graph')
    })
    expect(screen.getByText('无法渲染此图。', { exact: true })).toBeInTheDocument()
    expect(screen.queryByText('layout failed', { exact: false })).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: '图表' })).not.toBeInTheDocument()
  })

  it('attaches chart interactions and disposes them before unmount', async () => {
    const dispose = vi.fn()
    kchart.attach.mockResolvedValueOnce(dispose)
    const spec = { ...CHART_SPEC, interaction: ['inspect', 'legend'] }
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code={JSON.stringify(spec)} />)
    await expectSvg(view.container, 'data-chart="bar"')

    await waitFor(() => {
      expect(kchart.attach).toHaveBeenCalledTimes(1)
    })
    expect(kchart.attach.mock.calls[0]?.[1]).toEqual(spec)
    view.unmount()
    expect(dispose).toHaveBeenCalledTimes(1)
  })

  it('disposes the previous interaction before redrawing and drops a late attachment', async () => {
    const firstDispose = vi.fn()
    const secondDispose = vi.fn()
    const late = createDeferred<() => void>()
    kchart.attach
      .mockReturnValueOnce(late.promise)
      .mockResolvedValueOnce(secondDispose)
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
    const spec = { ...CHART_SPEC, interaction: ['inspect'] }
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code={JSON.stringify(spec)} />)

    resize(640)
    await waitFor(() => expect(kchart.attach).toHaveBeenCalledTimes(1))
    resize(720)
    await waitFor(() => expect(kchart.attach).toHaveBeenCalledTimes(2))
    expect(secondDispose).not.toHaveBeenCalled()

    late.resolve(firstDispose)
    await flushPromises()
    expect(firstDispose).toHaveBeenCalledTimes(1)

    view.unmount()
    expect(secondDispose).toHaveBeenCalledTimes(1)
  })

  it('does not attach interactions for a chart without interaction actions', async () => {
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code={JSON.stringify(CHART_SPEC)} />)
    await expectSvg(view.container, 'data-chart="bar"')
    await flushPromises()
    expect(kchart.attach).not.toHaveBeenCalled()
  })

  it('does not cache or write SVG after unmount cancellation', async () => {
    // 未完成渲染的组件卸载后不得写 DOM/cache；后续挂载必须重新渲染。
    const deferred = createDeferred<DiagramResult>()
    kchart.diagram
      .mockReturnValueOnce(deferred.promise)
      .mockResolvedValueOnce({ svg: '<svg data-source="second"></svg>', scene: { width: 1, height: 1, children: [] }, diagnostics: [] })
    const { VizBlock } = await loadVizBlock()
    const first = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)
    await waitFor(() => expect(kchart.diagram).toHaveBeenCalledTimes(1))
    first.unmount()

    deferred.resolve({ svg: '<svg data-source="cancelled"></svg>', scene: { width: 1, height: 1, children: [] }, diagnostics: [] })
    await flushPromises()

    const second = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)
    await expectSvg(second.container, 'second')
    expect(kchart.diagram).toHaveBeenCalledTimes(2)
  })

  it('evicts the oldest SVG after the bounded cache exceeds 48 entries', async () => {
    // cache 使用固定上限；最早源码被淘汰后再次挂载必须重新渲染。
    const { VizBlock } = await loadVizBlock()
    for (let index = 0; index < 49; index += 1) {
      const code = `graph TD; A${index}-->B${index}`
      const view = render(<VizBlock syntax="mermaid" code={code} />)
      await expectSvg(view.container, code)
      view.unmount()
    }

    const firstAgain = render(<VizBlock syntax="mermaid" code="graph TD; A0-->B0" />)
    await expectSvg(firstAgain.container, 'graph TD; A0-->B0')

    expect(kchart.diagram).toHaveBeenCalledTimes(50)
  })
})

function sceneOf(spec: ChartSpec): Scene {
  return {
    width: 320,
    height: 180,
    children: [],
    heading: typeof spec.title === 'string' ? spec.title : undefined,
    subtitle: typeof spec.subtitle === 'string' ? spec.subtitle : undefined,
    source: typeof spec.source === 'string' ? spec.source : undefined,
    note: typeof spec.note === 'string' ? spec.note : undefined,
  }
}

async function loadVizBlock() {
  return import('@/shared/ui/markdown/VizBlock')
}

async function expectSvg(container: HTMLElement, text: string) {
  await waitFor(() => {
    expect(container.querySelector('.md-viz')?.innerHTML).toContain(text)
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
