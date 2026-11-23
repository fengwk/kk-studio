import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SearchHitText } from '@/features/thread/SearchHitText'

describe('SearchHitText', () => {
  // Flatten multiline labels for display without dropping segment-boundary spaces or changing aria-label.
  it('normalizes whitespace without trimming or changing the hit structure', () => {
    const text = '  Before\n  first\t  and second\n'
    const { container } = render(
      <button aria-label={text}>
        <SearchHitText text={text} tokens={['first', 'second']} markClassName="test-mark" />
      </button>,
    )
    expect(screen.getByRole('button', { name: 'Before first and second' })).toHaveAttribute('aria-label', text)
    expect(container.textContent).toBe(' Before first and second ')
    expect(container.querySelector('.search-hit-prefix > [dir="ltr"]')?.textContent).toBe(' Before')
    expect(container.querySelector('.search-hit-head')?.textContent).toBe(' first')
    expect(container.querySelector('.search-hit-match > mark.test-mark')?.textContent).toBe('first')
    expect(container.querySelector('.search-hit-suffix')?.textContent).toBe(' and second ')
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
