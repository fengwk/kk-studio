import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ChartSpec,
  DiagramDiagnostic,
  DiagramOptions,
  RenderOptions,
  RenderResult,
  Scene,
} from '@fengwk/kchart'
import { installVizThemeFixture } from './viz-theme.test-fixture'

type DiagramResult = RenderResult<DiagramDiagnostic>
type AttachTarget = Parameters<typeof import('@fengwk/kchart').attach>[0]

const kchart = vi.hoisted(() => ({
  render: vi.fn<(spec: ChartSpec, options: RenderOptions) => Promise<RenderResult>>(),
  diagram: vi.fn<(source: string, options: DiagramOptions) => Promise<DiagramResult>>(),
  attach: vi.fn<(svg: AttachTarget, spec?: ChartSpec) => Promise<() => void>>(),
  themes: { terminal: { id: 'terminal', fg2: '#bdd7c4', tones: ['#aa7755'], style: { radius: 0, smallRadius: 0, soft: 0.12 } } },
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

installVizThemeFixture()

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
    // 用不同于应用默认值的 token 验证浏览器读取，且 Terminal 的系列色和其余样式不被覆盖。
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
      theme: {
        id: 'kk-studio',
        bg: '#101010', surface: '#202020', surface2: '#303030',
        line: '#404040', line2: '#505050',
        fg1: '#f0e0d0', fg2: '#bdd7c4', fg3: '#b0a090',
        accent: '#80dd90', ok: '#88bb88', warn: '#ffbb66', danger: '#ff8888', info: '#88ccff', ext: '#b0a090',
        fontFamily: '"Fixture Sans"',
        tones: kchart.themes.terminal.tones,
        style: { radius: 11, smallRadius: 7, soft: 0.12 },
      },
    })
    expect(options.measure?.('abc', 13, 400, '"Fixture Sans"')).toBe(21)
  })

  it.each(['layers', 'blocks', 'swimlane'] as const)('routes %s sources through diagram with that syntax', async (syntax) => {
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax={syntax} code="row" />)
    await expectSvg(view.container, 'data-source="row"')
    expect(kchart.diagram).toHaveBeenCalledWith('row', expect.objectContaining({ syntax }))
  })

  it('renders chart metadata around the SVG without rendering the returned table', async () => {
    // 即使 KChart 返回非空数据表，宿主也只显示图与元信息。
    kchart.render.mockImplementationOnce(async (spec) => ({
      svg: '<svg data-chart="bar"></svg>',
      scene: sceneOf(spec),
      table: DATA_TABLE,
      diagnostics: [],
    }))
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
    expect(view.container.querySelector('details, table')).toBeNull()
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
    const resize = controlResize()
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

  it('recovers on a new width after a layout failure without replacing the observed host', async () => {
    // 同一份源码的失败不应阻断后续宽度通知，也不能留下旧的图和元信息。
    const resize = controlResize()
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="kchart" code={JSON.stringify(CHART_SPEC)} />)
    resize(640)
    await expectSvg(view.container, 'data-width="640"')
    const host = view.container.querySelector('.md-viz')

    kchart.render.mockRejectedValueOnce(new Error('layout failed'))
    resize(360)
    expect(await screen.findByText('无法渲染此图。')).toBeInTheDocument()
    expect(view.container.querySelector('.md-viz')).toBe(host)
    expect(host?.querySelector('svg')).toBeNull()
    expect(screen.queryByText('发布节奏')).not.toBeInTheDocument()

    resize(480)
    await expectSvg(view.container, 'data-width="480"')
    expect(view.container.querySelector('.md-viz')).toBe(host)
    expect(screen.getByText('发布节奏')).toBeInTheDocument()
    expect(screen.queryByText('无法渲染此图。')).not.toBeInTheDocument()
  })

  it('ignores the first width A result after A/B/A and does not cache it', async () => {
    // 宽度相同不代表同一次请求；旧失败和仍在运行的 B 都不能覆盖新 A。
    const resize = controlResize()
    const first = createDeferred<DiagramResult>()
    const middle = createDeferred<DiagramResult>()
    kchart.diagram.mockReturnValueOnce(first.promise).mockReturnValueOnce(middle.promise)
    const { VizBlock } = await loadVizBlock()
    const view = render(<VizBlock syntax="mermaid" code="graph TD; A-->B" />)
    resize(480)
    await flushPromises()
    resize(360)
    await flushPromises()
    resize(480)
    await expectSvg(view.container, 'data-width="480"')
    const current = view.container.querySelector('.md-viz svg')

    first.resolve({ svg: null, scene: null, diagnostics: [] })
    middle.resolve({ svg: '<svg data-width="360"></svg>', scene: { width: 1, height: 1, children: [] }, diagnostics: [] })
    await flushPromises()
    expect(screen.queryByText('无法渲染此图。')).not.toBeInTheDocument()
    expect(view.container.querySelector('.md-viz svg')).toBe(current)

    resize(360)
    await expectSvg(view.container, 'data-width="360"')
    expect(kchart.diagram).toHaveBeenCalledTimes(4)
  })

  it.each(['source', 'syntax', 'locale'] as const)('clears the old figure and releases its attachment when %s changes', async (changed) => {
    // 让新图保持未完成，检查切换提交时，而不是下一张图绘制完成后，旧内容已消失。
    const { setLocale } = await import('@/shared/i18n')
    const { VizBlock } = await loadVizBlock()
    const spec = { ...CHART_SPEC, interaction: ['inspect'] }
    const code = JSON.stringify(spec)
    const dispose = vi.fn()
    kchart.attach.mockResolvedValueOnce(dispose)
    const view = render(<VizBlock syntax="kchart" code={code} />)
    await expectSvg(view.container, 'data-chart="bar"')
    await flushPromises()
    const previous = view.container.querySelector('.md-viz svg')
    dispose.mockImplementation(() => expect(previous?.isConnected).toBe(true))

    const pending = createDeferred<RenderResult>()
    kchart.render.mockReturnValueOnce(pending.promise)
    kchart.diagram.mockReturnValueOnce(createDeferred<DiagramResult>().promise)
    if (changed === 'locale') {
      act(() => setLocale('en-US'))
    } else {
      view.rerender(<VizBlock syntax={changed === 'syntax' ? 'mermaid' : 'kchart'} code={changed === 'source' ? JSON.stringify({ ...spec, title: 'Next' }) : code} />)
    }
    expect(view.container.querySelector('.md-viz svg')).toBeNull()
    expect(view.container.querySelector('.md-viz-heading, .md-viz-meta')).toBeNull()
    expect(dispose).toHaveBeenCalledTimes(1)
    if (changed === 'locale') {
      // 回到 A 命中缓存后，迟到的 B 也不能显示诊断或释放当前交互。
      await flushPromises()
      expect(kchart.render.mock.calls[1]?.[1]).toMatchObject({ lang: 'en-US' })
      act(() => setLocale('zh-CN'))
      await expectSvg(view.container, 'data-chart="bar"')
      pending.reject(new Error('stale locale'))
      await flushPromises()
      expect(view.container.querySelector('.md-viz-error')).toBeNull()
      expect(view.container.querySelector('.md-viz-heading')).toHaveTextContent('发布节奏')
    }
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
    const thirdDispose = vi.fn()
    const late = createDeferred<() => void>()
    kchart.attach
      .mockReturnValueOnce(late.promise)
      .mockResolvedValueOnce(secondDispose)
      .mockResolvedValueOnce(thirdDispose)
    const resize = controlResize()
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
    expect(secondDispose).not.toHaveBeenCalled()

    // 已完成的绑定先释放，再替换对应 SVG；迟到的清理不能覆盖当前绑定。
    const secondSvg = view.container.querySelector('.md-viz svg')
    secondDispose.mockImplementation(() => expect(secondSvg?.isConnected).toBe(true))
    resize(560)
    await waitFor(() => expect(kchart.attach).toHaveBeenCalledTimes(3))
    expect(secondDispose).toHaveBeenCalledTimes(1)
    expect(secondSvg?.isConnected).toBe(false)

    view.unmount()
    expect(thirdDispose).toHaveBeenCalledTimes(1)
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

function controlResize() {
  const observers = new Set<ResizeObserverCallback>()
  vi.stubGlobal('ResizeObserver', class {
    constructor(private readonly callback: ResizeObserverCallback) {}
    observe() { observers.add(this.callback) }
    disconnect() { observers.delete(this.callback) }
  })
  return (width: number) => act(() => {
    for (const callback of observers) {
      callback([{ contentRect: { width } } as ResizeObserverEntry], {} as ResizeObserver)
    }
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
