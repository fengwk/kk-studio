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
    /** UTF-8 size of `buffer`, which never contains a newline. */
    this.pendingBytes = 0
  }

  reset() {
    this.buffer = ''
    this.pendingBytes = 0
  }

  /**
   * Appends a chunk and passes each decoded line to `onMessage` in arrival order. The callback
   * returns `false` to stop consuming the chunk. Returns `CONSUMED`, `STOPPED`, or `INVALID`.
   */
  push(chunk, onMessage) {
    // Walk the chunk by offset and copy only each delivered line plus the trailing partial line, so
    // a chunk carrying several lines is never re-sliced once per line.
    let start = 0
    let end = chunk.indexOf('\n')
    while (end >= 0) {
      const part = chunk.slice(start, end)
      // The bridge supplies UTF-8-decoded chunks, so code points do not straddle chunks.
      if (this.pendingBytes + Buffer.byteLength(part) > this.maxLineBytes) {
        return INVALID
      }
      const line = this.buffer + part
      this.reset()
      start = end + 1
      end = chunk.indexOf('\n', start)
      if (line.trim().length === 0) {
        continue
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
    if (start >= chunk.length) {
      return CONSUMED
    }
    const rest = start === 0 ? chunk : chunk.slice(start)
    const bytes = this.pendingBytes + Buffer.byteLength(rest)
    // An unterminated line beyond the bound is a protocol violation; never carry it forward.
    if (bytes > this.maxLineBytes) {
      return INVALID
    }
    this.buffer += rest
    this.pendingBytes = bytes
    return CONSUMED
  }
}

module.exports = { INVALID, LineFramer }
