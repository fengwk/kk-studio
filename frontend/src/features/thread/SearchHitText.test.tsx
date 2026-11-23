import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SearchHitText } from '@/features/thread/SearchHitText'

describe('SearchHitText', () => {
  // CSS may clip the prefix, but its whitespace and later hits stay in reading order in the DOM.
  it('keeps the entire text and wraps only hits in marks', () => {
    const text = 'Before  first and second'
    const { container } = render(<button aria-label={text}><SearchHitText text={text} tokens={['first', 'second']} markClassName="test-mark" /></button>)
    expect(screen.getByRole('button', { name: 'Before first and second' })).toBeInTheDocument()
    expect(container.textContent).toBe(text)
    expect(container.querySelector('.search-hit-prefix > [dir="ltr"]')?.textContent).toBe('Before')
    expect(container.querySelector('.search-hit-head')?.textContent).toBe('  first')
    expect(container.querySelector('.search-hit-match > mark.test-mark')?.textContent).toBe('first')
    expect(container.querySelector('.search-hit-suffix')?.textContent).toBe(' and second')
    expect(container.querySelector('.search-hit-suffix > mark.test-mark')?.textContent).toBe('second')
  })

  it.each([['first', 'First'], ['missing', undefined]])('keeps the text when the first hit starts the text or is absent (%s)', (token, mark) => {
    const { container } = render(<SearchHitText text="First words" tokens={[token]} markClassName="test-mark" />)
    expect(container.querySelector('.search-hit-prefix')).toBeNull()
    expect(container.textContent).toBe('First words')
    expect(container.querySelector('.search-hit-match')?.textContent).toBe(mark)
    expect(container.querySelector('mark')?.textContent).toBe(mark)
  })
})
