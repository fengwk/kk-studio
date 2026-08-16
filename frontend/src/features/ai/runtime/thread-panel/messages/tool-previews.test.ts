import { describe, expect, it } from 'vitest'
import {
  formatEditPreview,
  formatWritePreview,
  generateSimpleDiffLines,
  parseToolArguments,
  previewForToolCall,
} from '@/features/ai/runtime/thread-panel/messages/tool-previews'

describe('tool previews', () => {
  it('parses complete write arguments and keeps the full content', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'SortingAlgorithms.java',
      content: 'line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\n',
    }))
    const preview = formatWritePreview(parsed)

    expect(parsed.complete).toBe(true)
    expect(preview.path).toBe('SortingAlgorithms.java')
    expect(preview.lines).toEqual([
      'line1',
      'line2',
      'line3',
      'line4',
      'line5',
      'line6',
      'line7',
      'line8',
    ])
  })

  it('keeps parsed content while write arguments are still streaming', () => {
    const parsed = parseToolArguments('{"path":"App.java","content":"one\\ntwo\\nthree\\nfour\\nfive\\nsix\\nseven\\neight')
    const preview = formatWritePreview(parsed)

    expect(parsed.complete).toBe(false)
    expect(preview.path).toBe('App.java')
    expect(preview.lines).toEqual(['one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight'])
  })

  it('renders an edit preview as a numbered-style diff instead of raw JSON', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'App.java',
      old_string: 'alpha\nbeta\ngamma',
      new_string: 'alpha\nBETA\ngamma',
    }))
    const preview = formatEditPreview(parsed)

    expect(previewForToolCall('edit', parsed.raw)?.kind).toBe('edit')
    expect(preview.path).toBe('App.java')
    expect(generateSimpleDiffLines('alpha\nbeta\ngamma', 'alpha\nBETA\ngamma')).toEqual([
      ' alpha',
      '-beta',
      '+BETA',
      ' gamma',
    ])
    expect(preview.lines).toEqual([
      ' alpha',
      '-beta',
      '+BETA',
      ' gamma',
    ])
  })
})
