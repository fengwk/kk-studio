import { describe, expect, it } from 'vitest'
import { splitMarkdownSegments } from '@/shared/ui/markdown/splitMarkdownSegments'

describe('splitMarkdownSegments', () => {
  it('keeps incomplete mermaid fence inside markdown segment', () => {
    const segments = splitMarkdownSegments(['intro', '```mermaid', 'graph TD', '  A-->B'].join('\n'))
    expect(segments).toHaveLength(1)
    expect(segments[0]?.type).toBe('markdown')
    expect(segments[0]?.content).toContain('```mermaid')
  })

  it('extracts closed mermaid fence and keeps surrounding markdown stable keys for tail growth', () => {
    const base = ['hello', '', '```mermaid', 'graph TD', '  A-->B', '```', '', 'tail'].join('\n')
    const grown = `${base} more`
    const a = splitMarkdownSegments(base)
    const b = splitMarkdownSegments(grown)
    expect(a.map((s) => s.type)).toEqual(['markdown', 'mermaid', 'markdown'])
    expect(b.map((s) => s.type)).toEqual(['markdown', 'mermaid', 'markdown'])
    // 图段 key/code 不变，后续只改尾部 markdown
    expect(a[1]?.key).toBe(b[1]?.key)
    expect(a[1]?.content).toBe(b[1]?.content)
    expect(b[2]?.content).toContain('tail more')
  })
})
