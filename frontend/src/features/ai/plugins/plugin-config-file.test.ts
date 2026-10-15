import { describe, expect, it, vi } from 'vitest'
import { readStrictJsonFile, type JsonFileSource } from './plugin-config-file'
import { MAX_PLUGIN_VALUES_UTF8_BYTES } from './plugin-config-json'

/** 只提供字节大小与原始字节的最小文件替身：够用即可，不复制 File API。 */
function sourceOf(bytes: Uint8Array | number[], size?: number): JsonFileSource {
  const array = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
  return {
    size: size ?? array.byteLength,
    arrayBuffer: () => Promise.resolve(array.slice().buffer as ArrayBuffer),
  }
}

function encoded(text: string): Uint8Array {
  return new TextEncoder().encode(text)
}

describe('readStrictJsonFile', () => {
  it('returns the decoded text for a strict UTF-8 JSON object or array', async () => {
    const objectFile = await readStrictJsonFile(sourceOf(encoded('{"a":1,"b":[2,3]}')))
    expect(objectFile).toEqual({ ok: true, text: '{"a":1,"b":[2,3]}' })

    const arrayFile = await readStrictJsonFile(sourceOf(encoded('[1,2]')))
    expect(arrayFile.ok).toBe(true)
  })

  it('strips a leading BOM instead of failing an otherwise legal file', async () => {
    const withBom = new Uint8Array([0xef, 0xbb, 0xbf, ...encoded('{"a":1}')])
    const result = await readStrictJsonFile(sourceOf(withBom))
    expect(result).toEqual({ ok: true, text: '{"a":1}' })
  })

  it('rejects files above the configuration byte budget before reading any content', async () => {
    const arrayBuffer = vi.fn(() => Promise.resolve(new ArrayBuffer(0)))
    const result = await readStrictJsonFile({
      size: MAX_PLUGIN_VALUES_UTF8_BYTES + 1,
      arrayBuffer,
    })
    expect(result).toEqual({ ok: false, reason: 'too-large' })
    // 超限文件在读取前就被拒绝，绝不物化内容。
    expect(arrayBuffer).not.toHaveBeenCalled()
  })

  it('rejects oversized content even when the declared size understates it', async () => {
    const padded = `{"a":"${'x'.repeat(MAX_PLUGIN_VALUES_UTF8_BYTES)}"}`
    const result = await readStrictJsonFile(sourceOf(encoded(padded), 2))
    expect(result).toEqual({ ok: false, reason: 'too-large' })
  })

  it('rejects byte sequences that are not strict UTF-8', async () => {
    // 0xff 不是任何 UTF-8 序列的合法起始字节：必须拒绝而不是替换成 U+FFFD。
    const result = await readStrictJsonFile(sourceOf([0x7b, 0xff, 0x7d]))
    expect(result).toEqual({ ok: false, reason: 'not-utf8' })
  })

  it('rejects invalid JSON syntax with the strict parser diagnostic', async () => {
    for (const text of ['{"a":1,}', '{"a":1 /* c */}', '{"a":1,"a":2}', '123', '', '   ']) {
      const result = await readStrictJsonFile(sourceOf(encoded(text)))
      expect(result.ok, text).toBe(false)
      if (!result.ok) {
        expect(result.reason).toBe('invalid-json')
        expect(result.detail).toBeTruthy()
      }
    }
  })

  it('reports read failures without inventing content', async () => {
    const result = await readStrictJsonFile({
      size: 4,
      arrayBuffer: () => Promise.reject(new Error('device error')),
    })
    expect(result).toEqual({ ok: false, reason: 'read-failed' })
  })
})
