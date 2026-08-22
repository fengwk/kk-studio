import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'

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

describe('MarkdownRenderer', () => {
  it('renders GFM table, bold text and inline code', () => {
    render(
      <MarkdownRenderer
        content={[
          '**hello** `code`',
          '',
          '| a | b |',
          '| - | - |',
          '| 1 | 2 |',
        ].join('\n')}
      />,
    )
    expect(screen.getByText('hello').tagName).toBe('STRONG')
    expect(screen.getByText('code').tagName).toBe('CODE')
    expect(screen.getByRole('table')).toBeInTheDocument()
  })

  it('returns null for blank content', () => {
    const { container } = render(<MarkdownRenderer content="   " />)
    expect(container).toBeEmptyDOMElement()
  })

  it('returns null when content is empty', () => {
    const { container } = render(<MarkdownRenderer content="" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders inline code without highlight classes or block shell', () => {
    const view = render(<MarkdownRenderer content="prefix `const x = 1` suffix" />)
    const code = screen.getByText('const x = 1')
    expect(code.tagName).toBe('CODE')
    expect(code.className).toContain('md-inline-code')
    expect(code.className).not.toContain('hljs')
    expect(view.container.querySelector('.md-code-shell')).toBeNull()
  })

  it('renders TS fenced code as a highlighted block', () => {
    const view = render(
      <MarkdownRenderer
        content={[
          '```ts',
          'const value: number = 1',
          'const doubled = value * 2',
          '```',
        ].join('\n')}
      />,
    )
    const shell = view.container.querySelector('.md-code-shell')
    expect(shell).not.toBeNull()
    expect(shell?.querySelector('code.language-ts')).not.toBeNull()
    expect(shell?.querySelector('code.hljs')).not.toBeNull()
    expect(shell?.querySelectorAll('.hljs-keyword').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: '复制代码' })).toBeInTheDocument()
  })

  it('renders a closed mermaid fence through MermaidBlock', async () => {
    const view = render(
      <MarkdownRenderer
        content={['intro', '', '```mermaid', 'graph TD; A-->B', '```', '', 'outro'].join('\n')}
      />,
    )
    expect(screen.getByText('intro')).toBeInTheDocument()
    expect(screen.getByText('outro')).toBeInTheDocument()

    await waitFor(() => {
      expect(view.container.querySelector('.md-mermaid')?.innerHTML).toContain('graph TD; A-->B')
    })
    expect(mermaidApi.render).toHaveBeenCalledWith(
      expect.stringMatching(/^mmd[a-zA-Z0-9_-]+$/),
      'graph TD; A-->B',
    )
  })

  it('does not re-parse markdown when the same content rerenders', () => {
    // 流式追加时前缀段 content 不变：memo 比较器短路，跳过整段 re-parse。
    const view = render(<MarkdownRenderer content="stable **text**" />)
    expect(view.container.querySelector('.md-root')?.children).toHaveLength(1)

    view.rerender(<MarkdownRenderer content="stable **text**" />)
    expect(view.container.querySelector('.md-root')?.children).toHaveLength(1)
  })

  it('renders adjacent closed fences and skips blank md segments', async () => {
    // 相邻闭合 fence 之间只有空行：该 md 段被跳过，每段独立成图。
    // 用全新源码避免命中其他用例写入的模块级 svgCache。
    const view = render(
      <MarkdownRenderer
        content={[
          '```mermaid',
          'graph LR; A-->C',
          '```',
          '```mermaid',
          'sequenceDiagram; X-->Y',
          '```',
        ].join('\n')}
      />,
    )
    await waitFor(() => {
      expect(view.container.querySelectorAll('.md-mermaid')).toHaveLength(2)
    })
    await waitFor(() => {
      expect(view.container.querySelector('.md-mermaid')?.innerHTML).toContain('graph LR; A-->C')
    })
    expect(mermaidApi.render).toHaveBeenCalledTimes(2)
    expect(mermaidApi.render).toHaveBeenCalledWith(
      expect.stringMatching(/^mmd[a-zA-Z0-9_-]+$/),
      'graph LR; A-->C',
    )
  })

  it('renders an unclosed mermaid fence as a plain code block', () => {
    const view = render(
      <MarkdownRenderer content={['```mermaid', 'graph TD; A-->B'].join('\n')} />,
    )
    const shell = view.container.querySelector('.md-code-shell')
    expect(shell).not.toBeNull()
    // mermaid 不是已注册的高亮语言：rehype-highlight 保留 hljs 前缀 class 但产出无高亮 token，也绝不走 MermaidBlock。
    expect(shell?.querySelector('code.language-mermaid')).not.toBeNull()
    expect(shell?.querySelector('.hljs-keyword')).toBeNull()
    expect(screen.getByRole('button', { name: '复制代码' })).toBeInTheDocument()
    expect(mermaidApi.render).not.toHaveBeenCalled()
  })

  it('opens external links in a new tab with safe rel', () => {
    render(<MarkdownRenderer content="[docs](https://example.com/guide)" />)
    const link = screen.getByRole('link', { name: 'docs' })
    expect(link).toHaveAttribute('href', 'https://example.com/guide')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noreferrer noopener')
  })

  it('renders KaTeX math', () => {
    const view = render(
      <MarkdownRenderer
        content={['inline $a^2+b^2=c^2$', '', '$$', 'E=mc^2', '$$'].join('\n')}
      />,
    )
    // 生产配置 output:'html'，KaTeX 只产出 katex-html 渲染树。
    expect(view.container.querySelectorAll('.katex')).toHaveLength(2)
    expect(view.container.querySelector('.katex-html')?.textContent).toContain('a')
    expect(view.container.querySelector('.katex-display')).not.toBeNull()
    expect(
      view.container.querySelector('.katex-display .katex-html')?.textContent,
    ).toContain('E')
  })

  it('applies tone and className to the root', () => {
    const view = render(
      <MarkdownRenderer content="text" tone="muted" className="custom-md" />,
    )
    const root = view.container.querySelector('.md-root')
    expect(root).not.toBeNull()
    expect(root?.classList.contains('md-tone-muted')).toBe(true)
    expect(root?.classList.contains('custom-md')).toBe(true)
  })

  it('falls back to the source view when Mermaid rendering rejects', async () => {
    mermaidApi.render.mockRejectedValueOnce(new Error('parse failed'))
    const view = render(<MarkdownRenderer content={'```mermaid\nnot a graph\n```'} />)

    await waitFor(() => {
      expect(view.container.querySelector('code.language-mermaid')).toHaveTextContent('not a graph')
    })
    expect(screen.queryByRole('img', { name: 'Mermaid 图表' })).not.toBeInTheDocument()
  })
})
