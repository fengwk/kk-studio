import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SearchHitText } from '@/features/thread/SearchHitText'

describe('SearchHitText', () => {
  // CSS may clip the prefix, but its whitespace and later hits stay in reading order in the DOM.
  it('keeps the entire text and wraps only hits in marks', () => {
    const text = 'Before  first and second'
    const { container } = render(<SearchHitText text={text} tokens={['first', 'second']} markClassName="test-mark" />)
    expect(container.textContent).toBe(text)
    expect(container.querySelector('.search-hit-prefix > [dir="ltr"]')?.textContent).toBe('Before  ')
    const match = container.querySelector('.search-hit-match')!
    expect(match.textContent).toBe('first and second')
    expect(Array.from(match.querySelectorAll('mark.test-mark'), (mark) => mark.textContent))
      .toEqual(['first', 'second'])
  })

  it.each([['first', 'First'], ['missing', undefined]])('uses one segment for %s', (token, mark) => {
    const { container } = render(<SearchHitText text="First words" tokens={[token]} markClassName="test-mark" />)
    expect(container.querySelector('.search-hit-prefix')).toBeNull()
    expect(container.querySelector('.search-hit-match')?.textContent).toBe('First words')
    expect(container.querySelector('mark')?.textContent).toBe(mark)
  })
})
