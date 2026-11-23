import { splitSearchHit } from '@/features/thread/search-highlight'
import './search-hit-text.css'

interface SearchHitTextProps {
  text: string
  tokens: string[]
  markClassName: string
}

/** Visual clipping never removes text from the DOM. */
export function SearchHitText({ text, tokens, markClassName }: SearchHitTextProps) {
  // Render on one line (e.g. for Agent descriptions) while preserving spaces at segment boundaries.
  // Callers set aria-label from the original text, independently of this visual normalization.
  const { prefix, spaceBeforeHit, hit, suffix } = splitSearchHit(text.replace(/\s+/g, ' '), tokens)
  return (
    <span className="search-hit-text">
      {prefix !== '' ? <span className="search-hit-prefix"><span dir="ltr">{prefix}</span></span> : null}
      <span className="search-hit-remainder">
        {hit !== '' ? (
          <span className="search-hit-head">
            {spaceBeforeHit !== '' ? <span className="search-hit-space">{spaceBeforeHit}</span> : null}
            <span className="search-hit-match"><mark className={markClassName}>{hit}</mark></span>
          </span>
        ) : null}
        {suffix.length > 0 ? (
          <span className="search-hit-suffix">
            {suffix.map((segment, index) => segment.match
              ? <mark key={index} className={markClassName}>{segment.text}</mark>
              : segment.text)}
          </span>
        ) : null}
      </span>
    </span>
  )
}
