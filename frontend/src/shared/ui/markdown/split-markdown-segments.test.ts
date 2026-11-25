import { describe, expect, it } from 'vitest'
import { splitMarkdownSegments } from '@/shared/ui/markdown/split-markdown-segments'

const SYNTAXES = ['kchart', 'mermaid', 'layers', 'blocks', 'swimlane'] as const

describe('splitMarkdownSegments', () => {
  it.each(SYNTAXES)('extracts a closed %s fence as a viz segment', (syntax) => {
    const segments = splitMarkdownSegments([`\`\`\`${syntax}`, 'source', '```'].join('\n'))
    expect(segments).toEqual([{
      type: 'viz',
      syntax,
      content: 'source',
      key: `viz-${syntax}-${hashString('source')}`,
    }])
  })

  it('normalizes the language name to lowercase and keeps a language-sensitive key', () => {
    const lower = splitMarkdownSegments(['```kchart', '{"chart":"bar"}', '```'].join('\n'))
    const upper = splitMarkdownSegments(['```KCHART', '{"chart":"bar"}', '```'].join('\n'))
    expect(upper).toEqual(lower)
    expect(lower[0]).toMatchObject({ type: 'viz', syntax: 'kchart' })
  })

  it('keeps an incomplete fence inside the markdown segment', () => {
    for (const syntax of SYNTAXES) {
      const segments = splitMarkdownSegments(['intro', `\`\`\`${syntax}`, 'incomplete'].join('\n'))
      expect(segments).toHaveLength(1)
      expect(segments[0]?.type).toBe('markdown')
      expect(segments[0]?.content).toContain(`\`\`\`${syntax}`)
    }
  })

  it('leaves other fenced languages untouched', () => {
    const source = ['```ts', 'const value = 1', '```', '```mermaid', 'graph TD; A-->B', '```'].join('\n')
    const segments = splitMarkdownSegments(source)
    expect(segments.map((segment) => segment.type)).toEqual(['markdown', 'viz'])
    expect(segments[0]?.content).toContain('```ts')
    expect(segments[0]?.content).toContain('const value = 1')
  })

  it('does not extract viz-looking lines nested inside another fence', () => {
    const source = [
      '````ts',
      'const sample = `',
      '```mermaid',
      'graph TD; A-->B',
      '```',
      '`',
      '````',
      '',
      '```blocks',
      'row',
      '```',
    ].join('\n')
    const segments = splitMarkdownSegments(source)
    expect(segments.map((segment) => segment.type)).toEqual(['markdown', 'viz'])
    expect(segments[0]?.content).toContain('```mermaid')
    expect(segments[1]).toMatchObject({ syntax: 'blocks', content: 'row' })
  })

  it('does not extract backtick fences nested inside a tilde fence', () => {
    const source = ['~~~text', '```mermaid', 'graph TD; A-->B', '```', '~~~'].join('\n')
    expect(splitMarkdownSegments(source)).toEqual([{ type: 'markdown', content: source, key: 'md-0' }])
  })

  it('splits mixed fences and keeps surrounding markdown stable for tail growth', () => {
    const base = [
      'hello',
      '',
      '```mermaid',
      'graph TD',
      '  A-->B',
      '```',
      '',
      '```kchart',
      '{"chart":"bar"}',
      '```',
      '',
      '```blocks',
      'row',
      '```',
      '',
      'tail',
    ].join('\n')
    const a = splitMarkdownSegments(base)
    const b = splitMarkdownSegments(`${base} more`)
    expect(a.map((segment) => segment.type)).toEqual(['markdown', 'viz', 'markdown', 'viz', 'markdown', 'viz', 'markdown'])
    expect(a.slice(0, 6)).toEqual(b.slice(0, 6))
    expect(b.at(-1)?.content).toContain('tail more')
    expect(a[1]).toMatchObject({ syntax: 'mermaid', content: 'graph TD\n  A-->B' })
    expect(a[3]).toMatchObject({ syntax: 'kchart', content: '{"chart":"bar"}' })
    expect(a[5]).toMatchObject({ syntax: 'blocks', content: 'row' })
  })
})

function hashString(value: string): string {
  let hash = 0
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash << 5) - hash + value.charCodeAt(i)
    hash |= 0
  }
  return Math.abs(hash).toString(36)
}
