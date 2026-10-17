import { LspError } from './lsp-error.js'

export interface DecodedText {
  text: string
  charset: string
  bomLength: number
}

export class TextFileCodec {
  static decode(bytes: Uint8Array): DecodedText {
    if (this.starts(bytes, 0xef, 0xbb, 0xbf)) {
      return this.decodeStrict(bytes, 3, 'utf-8')
    }
    if (this.starts(bytes, 0xff, 0xfe)) {
      return this.decodeStrict(bytes, 2, 'utf-16le')
    }
    if (this.starts(bytes, 0xfe, 0xff)) {
      return this.decodeStrict(bytes, 2, 'utf-16be')
    }
    if (this.isBinary(bytes)) {
      throw new LspError('file appears to be binary')
    }
    return this.decodeStrict(bytes, 0, 'utf-8')
  }

  static isBinary(bytes: Uint8Array): boolean {
    const scan = Math.min(bytes.length, 8192)
    for (let index = 0; index < scan; index++) {
      const value = bytes[index]!
      if (value === 0 || value < 0x09 || (value > 0x0d && value < 0x20 && value !== 0x1b)) {
        return true
      }
    }
    return false
  }

  private static decodeStrict(bytes: Uint8Array, bomLength: number, charset: string): DecodedText {
    const payload = bytes.subarray(bomLength)
    let text: string
    try {
      const decoder = new TextDecoder(charset, { fatal: true })
      text = decoder.decode(payload)
    } catch {
      throw new LspError('file appears to be binary: invalid text encoding')
    }
    if (text.includes('\u0000')) {
      throw new LspError('file appears to be binary: contains NUL character')
    }
    return { text, charset, bomLength }
  }

  private static starts(bytes: Uint8Array, ...prefix: number[]): boolean {
    if (bytes.length < prefix.length) {
      return false
    }
    for (let index = 0; index < prefix.length; index++) {
      if (bytes[index] !== prefix[index]) {
        return false
      }
    }
    return true
  }
}
