import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'

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
})
