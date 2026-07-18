export class ThinkTagTextFilter {
  private static readonly OPEN_TAG = '<think>'
  private static readonly CLOSE_TAG = '</think>'

  private carry = ''
  private inThinking = false
  private suppressLeadingWhitespace = false

  append(chunk: string): string {
    if (!chunk) {
      return ''
    }
    this.carry += chunk
    return this.drain(false)
  }

  finish(): string {
    return this.drain(true)
  }

  private drain(flushAll: boolean): string {
    let text = ''
    while (this.carry.length > 0) {
      if (this.inThinking) {
        const closeIndex = this.carry.indexOf(ThinkTagTextFilter.CLOSE_TAG)
        if (closeIndex >= 0) {
          this.carry = this.carry.slice(closeIndex + ThinkTagTextFilter.CLOSE_TAG.length)
          this.inThinking = false
          this.suppressLeadingWhitespace = true
          continue
        }
        const keep = flushAll ? 0 : this.longestTagPrefixSuffix(ThinkTagTextFilter.CLOSE_TAG)
        const emitLength = this.carry.length - keep
        if (emitLength <= 0) {
          break
        }
        this.carry = this.carry.slice(emitLength)
        continue
      }

      const openIndex = this.carry.indexOf(ThinkTagTextFilter.OPEN_TAG)
      if (openIndex >= 0) {
        text += this.normalizeVisibleText(this.carry.slice(0, openIndex))
        this.carry = this.carry.slice(openIndex + ThinkTagTextFilter.OPEN_TAG.length)
        this.inThinking = true
        continue
      }

      const keep = flushAll ? 0 : this.longestTagPrefixSuffix(ThinkTagTextFilter.OPEN_TAG)
      const emitLength = this.carry.length - keep
      if (emitLength <= 0) {
        break
      }
      text += this.normalizeVisibleText(this.carry.slice(0, emitLength))
      this.carry = this.carry.slice(emitLength)
    }
    return text
  }

  private normalizeVisibleText(value: string): string {
    if (!value) {
      return ''
    }
    if (!this.suppressLeadingWhitespace) {
      return value
    }
    const normalized = value.replace(/^\s+/, '')
    if (!normalized) {
      return ''
    }
    this.suppressLeadingWhitespace = false
    return normalized
  }

  private longestTagPrefixSuffix(tag: string): number {
    const max = Math.min(this.carry.length, tag.length - 1)
    for (let length = max; length > 0; length -= 1) {
      if (this.carry.slice(-length) === tag.slice(0, length)) {
        return length
      }
    }
    return 0
  }
}
