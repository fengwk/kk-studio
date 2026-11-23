import { describe, expect, it } from 'vitest'
import { highlightSegments, splitSearchHit } from '@/features/thread/search-highlight'

describe('search highlight', () => {
  it('splits a preview into merged highlight segments', () => {
    const segments = highlightSegments('fix the parser parser bug', ['parser', 'fix'])
    expect(segments.map((segment) => segment.text).join('')).toBe('fix the parser parser bug')
    expect(segments.filter((segment) => segment.match).map((segment) => segment.text))
      .toEqual(['fix', 'parser', 'parser'])
    expect(highlightSegments('nothing', ['parser'])).toEqual([{ text: 'nothing', match: false }])
  })

  // The split follows text order, not token order, without dropping boundary whitespace.
  it('separates the prefix and boundary space from the first hit and suffix', () => {
    expect(splitSearchHit('Checks  Pull requests', ['requests', 'pull'])).toEqual({
      prefix: 'Checks',
      spaceBeforeHit: '  ',
      hit: 'Pull',
      suffix: [
        { text: ' ', match: false },
        { text: 'requests', match: true },
      ],
    })
  })

  it('does not split when there is no hit or when the hit starts the text', () => {
    expect(splitSearchHit('main', ['parser'])).toEqual({ prefix: '', spaceBeforeHit: '', hit: '', suffix: [{ text: 'main', match: false }] })
    expect(splitSearchHit('main Branch', ['main', 'branch'])).toEqual({
      prefix: '',
      spaceBeforeHit: '',
      hit: 'main',
      suffix: [
        { text: ' ', match: false },
        { text: 'Branch', match: true },
      ],
    })
  })
})
