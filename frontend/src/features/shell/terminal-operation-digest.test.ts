import { describe, expect, it } from 'vitest'
import {
  inputOperationDigest,
  resizeOperationDigest,
} from '@/features/shell/terminal-operation-digest'

// 期望值以 Node `crypto.createHash('sha256')` 独立复算得到，且与 Daemon
// TerminalWriter.inputDigest/resizeDigest 的字节序列一致，作为跨语言 Oracle。
const ABC_DIGEST = '9ef670af8f777d4b958768c61d89b5246353f7b0133c5de4e4fabff8c9c25bbe'
const ABC_MAX_MODE_DIGEST = 'cf1f127ea770178aa551b6d25780461b9286b8a05924c037011beeda51f1fbd8'
const RESIZE_80_24_DIGEST = '46c3b27fd798ddce8405a8289023055750ce79abaea7550a14c1f13b9ea1abe6'

const abc = new Uint8Array([0x61, 0x62, 0x63])

describe('terminal operation digest', () => {
  it('matches the Daemon INPUT vector for 0x01 + int64 modeRevision + bytes', () => {
    expect(inputOperationDigest(abc, 1)).toBe(ABC_DIGEST)
  })

  it('encodes the full safe-integer range as big-endian int64', () => {
    expect(inputOperationDigest(abc, Number.MAX_SAFE_INTEGER)).toBe(ABC_MAX_MODE_DIGEST)
  })

  it('matches the Daemon RESIZE vector for 0x02 + int32 cols + int32 rows', () => {
    expect(resizeOperationDigest(80, 24)).toBe(RESIZE_80_24_DIGEST)
  })

  it('distinguishes INPUT from RESIZE and different sizes', () => {
    expect(inputOperationDigest(new Uint8Array([0x02]), 1)).not.toBe(
      resizeOperationDigest(5, 2),
    )
    expect(resizeOperationDigest(80, 24)).not.toBe(resizeOperationDigest(24, 80))
  })

  it('rejects empty or non-Uint8Array input and non-positive mode revisions', () => {
    expect(() => inputOperationDigest(new Uint8Array(0), 1)).toThrow(RangeError)
    expect(() => inputOperationDigest(abc, 0)).toThrow(RangeError)
    expect(() => inputOperationDigest(abc, 1.5)).toThrow(RangeError)
    expect(() => inputOperationDigest('abc' as unknown as Uint8Array, 1)).toThrow(RangeError)
  })

  it('rejects resize sizes outside the authoritative 5..300 x 2..100 range', () => {
    expect(() => resizeOperationDigest(4, 24)).toThrow(RangeError)
    expect(() => resizeOperationDigest(301, 24)).toThrow(RangeError)
    expect(() => resizeOperationDigest(80, 1)).toThrow(RangeError)
    expect(() => resizeOperationDigest(80, 101)).toThrow(RangeError)
    expect(() => resizeOperationDigest(80.5, 24)).toThrow(RangeError)
  })
})
