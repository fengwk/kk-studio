'use strict'

/**
 * Incremental NDJSON line framing for the bridge control stream.
 *
 * The daemon sends one JSON object per line. A frame that is not newline-terminated within the
 * byte budget, or that is not valid JSON, is a protocol violation: the caller must abandon the
 * stream rather than carry the partial data forward. `Eof` reports a clean end of stream.
 */

class LineFramer {
  constructor(maxLineBytes) {
    this.maxLineBytes = maxLineBytes
    this.buffer = ''
  }

  reset() {
    this.buffer = ''
  }

  /**
   * Appends a chunk and returns the decoded messages, or `null` when the stream is unusable.
   * A `{type: 'retired'}` frame is returned like any other message; the caller decides its effect.
   */
  push(chunk) {
    this.buffer += chunk
    // An unterminated line beyond the bound is a protocol violation; never carry it forward.
    if (this.buffer.indexOf('\n') === -1 && this.buffer.length > this.maxLineBytes) {
      return null
    }
    const messages = []
    let index = this.buffer.indexOf('\n')
    while (index >= 0) {
      const line = this.buffer.slice(0, index)
      this.buffer = this.buffer.slice(index + 1)
      index = this.buffer.indexOf('\n')
      if (line.trim().length === 0) {
        continue
      }
      if (line.length > this.maxLineBytes) {
        return null
      }
      try {
        messages.push(JSON.parse(line))
      } catch {
        return null
      }
    }
    return messages
  }
}

module.exports = { LineFramer }
