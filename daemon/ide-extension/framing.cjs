'use strict'

/**
 * Incremental NDJSON line framing for the bridge control stream.
 *
 * The daemon sends one JSON object per line. A frame that is not newline-terminated within the
 * byte budget, or that is not valid JSON, is a protocol violation: the caller must abandon the
 * stream rather than carry the partial data forward. `Eof` reports a clean end of stream.
 *
 * Lines are delivered one at a time so the caller acts on each message as it arrives: a retired
 * frame stops consumption, and a violation only affects the lines after it.
 */

/** The whole chunk was decoded and delivered. */
const CONSUMED = 'consumed'
/** The consumer stopped before the chunk was exhausted; the remaining bytes are abandoned. */
const STOPPED = 'stopped'
/** A line broke the protocol; everything before it has already been delivered. */
const INVALID = 'invalid'

class LineFramer {
  constructor(maxLineBytes) {
    this.maxLineBytes = maxLineBytes
    this.buffer = ''
  }

  reset() {
    this.buffer = ''
  }

  /**
   * Appends a chunk and passes each decoded line to `onMessage` in arrival order. The callback
   * returns `false` to stop consuming the chunk. Returns `CONSUMED`, `STOPPED`, or `INVALID`.
   */
  push(chunk, onMessage) {
    this.buffer += chunk
    // An unterminated line beyond the bound is a protocol violation; never carry it forward.
    if (this.buffer.indexOf('\n') === -1 && this.buffer.length > this.maxLineBytes) {
      return INVALID
    }
    let index = this.buffer.indexOf('\n')
    while (index >= 0) {
      const line = this.buffer.slice(0, index)
      this.buffer = this.buffer.slice(index + 1)
      index = this.buffer.indexOf('\n')
      if (line.trim().length === 0) {
        continue
      }
      if (line.length > this.maxLineBytes) {
        return INVALID
      }
      let message
      try {
        message = JSON.parse(line)
      } catch {
        return INVALID
      }
      if (onMessage(message) === false) {
        return STOPPED
      }
    }
    return CONSUMED
  }
}

module.exports = { INVALID, LineFramer }
