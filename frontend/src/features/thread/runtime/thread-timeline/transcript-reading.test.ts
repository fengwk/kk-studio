import { describe, expect, it, vi } from 'vitest'
import {
  TRANSCRIPT_READING_INTENT_EVENT,
  announceTranscriptReading,
} from '@/features/thread/runtime/thread-timeline/transcript-reading'

describe('announceTranscriptReading', () => {
  it('bubbles a reading intent carrying its source', () => {
    const host = document.createElement('div')
    const child = document.createElement('span')
    host.appendChild(child)
    const listener = vi.fn()
    host.addEventListener(TRANSCRIPT_READING_INTENT_EVENT, listener)

    announceTranscriptReading(child, 'thinking')

    expect(listener).toHaveBeenCalledTimes(1)
    expect(listener.mock.calls[0]?.[0]).toMatchObject({
      bubbles: true,
      detail: { source: 'thinking' },
    })
  })
})
